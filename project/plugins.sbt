addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.12")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0")
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.8")

Compile / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "sbt-scala-benchmark/src/main/scala"
