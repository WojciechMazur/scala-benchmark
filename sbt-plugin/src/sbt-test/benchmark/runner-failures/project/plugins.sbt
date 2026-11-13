addSbtPlugin("org.scala-native" % "sbt-scala-benchmark" % sys.props("plugin.version"))
libraryDependencies += "com.lihaoyi" %% "ujson" % "4.0.2"
Compile / unmanagedSourceDirectories += file(sys.props("benchmark.scripted.support"))
