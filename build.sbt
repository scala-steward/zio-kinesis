val mainScala = "2.13.3"
val allScala  = Seq("2.12.11", mainScala)

// Allows to silence scalac compilation warnings selectively by code block or file path
// This is only compile time dependency, therefore it does not affect the generated bytecode
// https://github.com/ghik/silencer
lazy val silencer = {
  val Version = "1.7.1"
  Seq(
    compilerPlugin("com.github.ghik" % "silencer-plugin" % Version cross CrossVersion.full),
    "com.github.ghik" % "silencer-lib" % Version % Provided cross CrossVersion.full
  )
}

enablePlugins(ProtobufPlugin)
enablePlugins(GitVersioning)

inThisBuild(
  List(
    organization := "nl.vroste",
    homepage := Some(url("https://github.com/svroonland/zio-kinesis")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    scalaVersion := mainScala,
    crossScalaVersions := allScala,
    parallelExecution in Test := false,
    cancelable in Global := true,
    fork in Test := true,
    fork in run := true,
    publishMavenStyle := true,
    publishArtifact in Test :=
      false,
    assemblyJarName in assembly := "zio-kinesis-" + version.value + ".jar",
    test in assembly := {},
    target in assembly := file(baseDirectory.value + "/../bin/"),
    assemblyMergeStrategy in assembly := {
      case PathList("META-INF", xs @ _*)       => MergeStrategy.discard
      case n if n.startsWith("reference.conf") => MergeStrategy.concat
      case _                                   => MergeStrategy.first
    },
    bintrayOrganization := Some("vroste"),
    bintrayPackageLabels := Seq("zio", "kinesis", "aws"),
    bintrayVcsUrl := Some("https://github.com/svroonland/zio-kinesis"),
    resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
  )
)

name := "zio-kinesis"
scalafmtOnCompile := false

val zioAwsVersion = "3.15.34.1"

libraryDependencies ++= Seq(
  "dev.zio"                %% "zio"                         % "1.0.3",
  "dev.zio"                %% "zio-streams"                 % "1.0.3",
  "dev.zio"                %% "zio-test"                    % "1.0.3" % "test",
  "dev.zio"                %% "zio-test-sbt"                % "1.0.3" % "test",
  "dev.zio"                %% "zio-interop-reactivestreams" % "1.3.0.7-2",
  "dev.zio"                %% "zio-logging"                 % "0.5.3",
  "software.amazon.awssdk"  % "kinesis"                     % "2.15.35",
  "ch.qos.logback"          % "logback-classic"             % "1.2.3",
  "software.amazon.kinesis" % "amazon-kinesis-client"       % "2.2.11",
  "org.scala-lang.modules" %% "scala-collection-compat"     % "2.3.1",
  "org.hdrhistogram"        % "HdrHistogram"                % "2.1.12",
  "io.github.vigoo"        %% "zio-aws-core"                % zioAwsVersion,
  "io.github.vigoo"        %% "zio-aws-kinesis"             % zioAwsVersion,
  "io.github.vigoo"        %% "zio-aws-dynamodb"            % zioAwsVersion,
  "io.github.vigoo"        %% "zio-aws-cloudwatch"          % zioAwsVersion,
  "io.github.vigoo"        %% "zio-aws-netty"               % zioAwsVersion,
  "javax.xml.bind"          % "jaxb-api"                    % "2.3.1"
) ++ {
  if (scalaBinaryVersion.value == "2.13") silencer else Seq.empty
}

Compile / compile / scalacOptions ++= {
  if (scalaBinaryVersion.value == "2.13") Seq("-P:silencer:globalFilters=[import scala.collection.compat._]")
  else Seq.empty
}
Test / compile / scalacOptions ++= {
  if (scalaBinaryVersion.value == "2.13") Seq("-P:silencer:globalFilters=[import scala.collection.compat._]")
  else Seq.empty
}
Compile / doc / scalacOptions ++= {
  if (scalaBinaryVersion.value == "2.13") Seq("-P:silencer:globalFilters=[import scala.collection.compat._]")
  else Seq.empty
}

// Suppresses problems with Scaladoc @throws links
scalacOptions in (Compile, doc) ++= Seq("-no-link-warnings")

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")

lazy val interopFutures = (project in file("interop-futures"))
  .settings(
    name := "zio-kinesis-future",
    resolvers += Resolver.jcenterRepo,
    assemblyJarName in assembly := "zio-kinesis-future" + version.value + ".jar",
    libraryDependencies ++= Seq(
      "nl.vroste" %% "zio-kinesis"                 % "0.16.0",
      "dev.zio"   %% "zio-interop-reactivestreams" % "1.3.0.7-2"
    )
  )
