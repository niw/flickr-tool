name := "flickr-tool"

version := "0.1.0"

scalaVersion := "2.11.2"

libraryDependencies ++= Seq(
  "com.twitter" %% "util-core" % "6.22.1",
  "org.json4s" %% "json4s-native" % "3.2.11",
  "com.flickr4java" % "flickr4java" % "2.11"
)