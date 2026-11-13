lazy val benchmarks = FixtureBuild.matrix("js")
lazy val root = project
  .in(file("."))
  .settings(
    FixtureBuild.settings(benchmarks.js(sys.props("benchmark.scala.version")), "js")
  )
