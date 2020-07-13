ThisBuild / scalaVersion := "2.13.2"

val shared =
  crossProject(JSPlatform, JVMPlatform).crossType(CrossType.Pure).settings(
    libraryDependencies ++= Seq(
      "org.endpoints4s" %%% "algebra" % "1.0.0",
      "org.endpoints4s" %%% "json-schema-generic" % "1.0.0",
    )
  ).jvmSettings(
    libraryDependencies += "org.scala-js" %% "scalajs-stubs" % "1.0.0" % "provided",
  ).jsSettings(
    // the shared project contains classes (i.e. Counter and Increment) that are referenced in the exported API of the client project
    // -> the semanticdb plugin must be added and appropriately configured in the shared js project
    addCompilerPlugin("org.scalameta" % "semanticdb-scalac" % "4.3.10" cross CrossVersion.full),
    scalacOptions += "-Yrangepos",
    scalacOptions += "-P:semanticdb:text:on",
  )

val sharedJS = shared.js
val sharedJVM = shared.jvm

val client =
  project.enablePlugins(ScalaTsPlugin).settings(
    scalaTsModuleName := "scala-client",
    scalaTsConsiderFullCompileClassPath := true,
    // exclude the scala-ts-generator artifact
    // -> this may be unnecessary in future after that artifact does no more contain semanticdb information
    scalaTsExclude := java.util.regex.Pattern.compile("scala-ts-generator"),
    libraryDependencies += "org.endpoints4s" %%% "xhr-client" % "1.0.0+sjs1",
  ).dependsOn(sharedJS)

val server =
  project.settings(
    libraryDependencies ++= Seq(
      "org.endpoints4s" %% "akka-http-server" % "1.0.0",
    )
  ).dependsOn(sharedJVM)

lazy val root = project.in(file(".")).
  aggregate(client, server, sharedJS, sharedJVM).
  settings(
    publish := {},
    publishLocal := {},
  )