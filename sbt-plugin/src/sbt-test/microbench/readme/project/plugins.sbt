// The setup from the README: sbt-microbench brings sbt-jmh, sbt-scala-native and sbt-scalajs.
addSbtPlugin(sys.props("plugin.organization") % "sbt-microbench" % sys.props("plugin.version"))
