name := "assignment04-chat-server"

version := "0.1"

scalaVersion := "2.12.6"

scalaSource in Compile := baseDirectory.value / "src/main"

scalaSource in Test := baseDirectory.value / "src/test"

resourceDirectory in Compile := baseDirectory.value / "res"
