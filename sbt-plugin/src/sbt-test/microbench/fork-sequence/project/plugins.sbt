addSbtPlugin(sys.props("plugin.organization") % "sbt-microbench" % sys.props("plugin.version"))
libraryDependencies += "com.lihaoyi" %% "ujson" % "4.0.2"
Compile / unmanagedSourceDirectories += file(sys.props("microbench.scripted.support"))
