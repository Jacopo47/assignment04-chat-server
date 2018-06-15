name := """assignment04-chat-server"""

version := "0.1"

scalaVersion := "2.12.6"

scalaSource in Compile := baseDirectory.value / "src/main"

scalaSource in Test := baseDirectory.value / "src/test"

resourceDirectory in Compile := baseDirectory.value / "res"


libraryDependencies += "io.vertx" %% "vertx-lang-scala" % "3.5.2"
libraryDependencies += "io.vertx" %% "vertx-web-scala" % "3.5.2"
libraryDependencies += "io.vertx" % "vertx-platform" % "2.1.5" % "provided"
libraryDependencies += "com.chiradip.rediscl" % "redisclient_2.10" % "0.8"
libraryDependencies += "com.github.etaty" %% "rediscala" % "1.8.0"

libraryDependencies <+= scalaVersion("org.scala-lang" % "scala-compiler" % _ )
