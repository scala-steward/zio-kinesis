package nl.vroste.zio.kinesis.client.dynamicconsumer

import nl.vroste.zio.kinesis.client.dynamicconsumer.DynamicConsumer.Checkpointer
import nl.vroste.zio.kinesis.client.serde.Deserializer
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.kinesis.common.{ ConfigsBuilder, InitialPositionInStreamExtended }
import software.amazon.kinesis.coordinator.Scheduler
import software.amazon.kinesis.exceptions.ShutdownException
import software.amazon.kinesis.lifecycle.events._
import software.amazon.kinesis.processor.{
  RecordProcessorCheckpointer,
  ShardRecordProcessor,
  ShardRecordProcessorFactory
}
import software.amazon.kinesis.retrieval.KinesisClientRecord
import software.amazon.kinesis.retrieval.fanout.FanOutConfig
import software.amazon.kinesis.retrieval.polling.PollingConfig
import zio._
import zio.blocking.Blocking
import zio.logging.Logger
import zio.stream.ZStream

import scala.jdk.CollectionConverters._

private[client] class DynamicConsumerLive(
  logger: Logger[String],
  kinesisAsyncClient: KinesisAsyncClient,
  cloudWatchAsyncClient: CloudWatchAsyncClient,
  dynamoDbAsyncClient: DynamoDbAsyncClient
) extends DynamicConsumer.Service {
  override def shardedStream[R, T](
    streamName: String,
    applicationName: String,
    deserializer: Deserializer[R, T],
    requestShutdown: UIO[Unit],
    initialPosition: InitialPositionInStreamExtended,
    isEnhancedFanOut: Boolean,
    leaseTableName: Option[String],
    workerIdentifier: String,
    maxShardBufferSize: Int
  ): ZStream[
    Blocking with R,
    Throwable,
    (String, ZStream[Blocking, Throwable, DynamicConsumer.Record[T]], DynamicConsumer.Checkpointer)
  ] = {
    /*
     * A queue for a single Shard and interface between the KCL threadpool and the ZIO runtime
     *
     * This queue is used by a ZStream for a single Shard
     *
     * The Queue uses the error channel (E type parameter) to signal failure (Some[Throwable])
     * and completion (None)
     */
    class ShardQueue(
      val shardId: String,
      runtime: zio.Runtime[Any],
      val q: Queue[Exit[Option[Throwable], KinesisClientRecord]]
    ) {
      def offerRecords(r: java.util.List[KinesisClientRecord]): Unit =
        // Calls to q.offer will fail with an interruption error after the queue has been shutdown
        // TODO we must make sure never to throw an exception here, because KCL will delete the records
        // See https://github.com/awslabs/amazon-kinesis-client/issues/10
        runtime.unsafeRun {
          logger.debug(s"offerRecords for ${shardId} got ${r.size()} records") *>
            q.offerAll(r.asScala.map(Exit.succeed(_))).unit.catchSomeCause {
              case c if c.interrupted => ZIO.unit
            } *> // TODO what behavior do we want if the queue + substream are already shutdown for some reason..?
            logger.trace(s"offerRecords for ${shardId} COMPLETE")
        }

      def shutdownQueue: UIO[Unit] =
        logger.debug(s"shutdownQueue for ${shardId}") *>
          q.shutdown

      /**
       * Shutdown processing for this shard
       *
               * Clear everything that is still in the queue, offer a completion signal for the queue,
       * set an interrupt signal and await stream completion (in-flight messages processed)
       *
               */
      def stop(reason: String): Unit =
        runtime.unsafeRun {
          logger.debug(s"stop() for ${shardId} because of ${reason}") *>
            (
              q.takeAll.unit *>                  // Clear the queue so it doesn't have to be drained fully
                q.offer(Exit.fail(None)).unit <* // Pass an exit signal in the queue to stop the stream
                q.awaitShutdown
            ).race(
              q.awaitShutdown
            ) <*
            logger.trace(s"stop() for ${shardId} because of ${reason} - COMPLETE")
          // TODO maybe we want to only do this when the main stream's completion has bubbled up..?
        }
    }

    class ZioShardProcessorFactory(queues: Queues) extends ShardRecordProcessorFactory {
      override def shardRecordProcessor(): ShardRecordProcessor = new ZioShardProcessor(queues)
    }

    class ZioShardProcessor(queues: Queues) extends ShardRecordProcessor {
      var shardId: Option[String]        = None
      var shardQueue: Option[ShardQueue] = None

      override def initialize(input: InitializationInput): Unit =
        shardId = Some(input.shardId())

      override def processRecords(processRecordsInput: ProcessRecordsInput): Unit = {
        if (shardQueue.isEmpty)
          shardQueue = shardId.map(shardId => queues.newShard(shardId, processRecordsInput.checkpointer()))

        shardQueue.foreach(_.offerRecords(processRecordsInput.records()))
      }

      override def leaseLost(leaseLostInput: LeaseLostInput): Unit =
        shardQueue.foreach(_.stop("lease lost"))

      override def shardEnded(shardEndedInput: ShardEndedInput): Unit = {
        shardQueue.foreach(_.stop("shard ended"))
        shardEndedInput.checkpointer().checkpoint()
      }

      override def shutdownRequested(shutdownRequestedInput: ShutdownRequestedInput): Unit =
        shardQueue.foreach(_.stop("shutdown requested"))
    }

    class Queues(
      private val runtime: zio.Runtime[Any],
      val shards: Queue[Exit[Option[Throwable], (String, ShardQueue, Checkpointer)]]
    ) {
      def newShard(shard: String, checkpointer: RecordProcessorCheckpointer): ShardQueue =
        runtime.unsafeRun {
          for {
            queue        <- Queue
                       .bounded[Exit[Option[Throwable], KinesisClientRecord]](
                         maxShardBufferSize
                       )
                       .map(new ShardQueue(shard, runtime, _))
            checkpointer <- Checkpointer.make(checkpointer, logger)
            _            <- shards.offer(Exit.succeed((shard, queue, checkpointer))).unit
          } yield queue
        }

      def shutdown: UIO[Unit] =
        shards.offer(Exit.fail(None)).unit
    }

    object Queues {
      def make: ZManaged[Any, Nothing, Queues] =
        for {
          runtime <- ZIO.runtime[Any].toManaged_
          q       <- Queue.unbounded[Exit[Option[Throwable], (String, ShardQueue, Checkpointer)]].toManaged(_.shutdown)
        } yield new Queues(runtime, q)
    }

    def retrievalConfig(kinesisClient: KinesisAsyncClient) =
      if (isEnhancedFanOut)
        new FanOutConfig(kinesisClient)
          .streamName(streamName)
          .applicationName(applicationName)
      else
        new PollingConfig(streamName, kinesisClient)

    def toRecord(
      shardId: String,
      r: KinesisClientRecord
    ): ZIO[R, Throwable, DynamicConsumer.Record[T]] =
      deserializer.deserialize(Chunk.fromByteBuffer(r.data())).map { data =>
        DynamicConsumer.Record(
          shardId,
          r.sequenceNumber(),
          r.approximateArrivalTimestamp(),
          data,
          r.partitionKey(),
          r.encryptionType(),
          Option(r.subSequenceNumber()).filterNot(_ == 0L),
          Option(r.explicitHashKey()).filterNot(_.isEmpty),
          r.aggregated()
        )
      }

    // Run the scheduler
    val schedulerM =
      for {
        queues <- Queues.make

        configsBuilder = {
          val configsBuilder = new ConfigsBuilder(
            streamName,
            applicationName,
            kinesisAsyncClient,
            dynamoDbAsyncClient,
            cloudWatchAsyncClient,
            workerIdentifier,
            new ZioShardProcessorFactory(queues)
          )
          leaseTableName.fold(configsBuilder)(configsBuilder.tableName)
        }
        env           <- ZIO.environment[R].toManaged_

        scheduler <- Task(
                       new Scheduler(
                         configsBuilder.checkpointConfig(),
                         configsBuilder.coordinatorConfig(),
                         configsBuilder.leaseManagementConfig().initialPositionInStream(initialPosition),
                         configsBuilder.lifecycleConfig(),
                         configsBuilder.metricsConfig(),
                         configsBuilder.processorConfig(),
                         configsBuilder
                           .retrievalConfig()
                           .initialPositionInStreamExtended(initialPosition)
                           .retrievalSpecificConfig(retrievalConfig(kinesisAsyncClient))
                       )
                     ).toManaged_
        doShutdown = logger.debug("Starting graceful shutdown") *>
                       ZIO.fromFutureJava(scheduler.startGracefulShutdown()).unit.orDie <*
                       queues.shutdown
        _         <- zio.blocking
               .blocking(ZIO(scheduler.run()))
               .fork
               .flatMap(_.join)
               .onInterrupt(doShutdown)
               .forkManaged
        _         <- (requestShutdown *> doShutdown).forkManaged
      } yield ZStream
        .fromQueue(queues.shards)
        .flattenExitOption
        .map {
          case (shardId, shardQueue, checkpointer) =>
            val stream = ZStream
              .fromQueue(shardQueue.q)
              .ensuringFirst(shardQueue.shutdownQueue)
              .flattenExitOption
              .mapChunksM(_.mapM(toRecord(shardId, _)))
              .provide(env)
              .ensuringFirst(checkpointer.checkpoint.catchSome { case _: ShutdownException => UIO.unit }.orDie)

            (shardId, stream, checkpointer)
        }

    ZStream.unwrapManaged(schedulerM)

  }
}
