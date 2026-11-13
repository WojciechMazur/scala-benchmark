lazy val benchmarks = FixtureBuild
  .matrix("js")
  .settings(
    Compile / sources := (Compile / sources).value
      .filter(file => file.getName == "AggregationEdgeBenchmark.scala")
  )
lazy val root = project
  .in(file("."))
  .settings(
    AggregationChecks.settings(benchmarks.js(sys.props("benchmark.scala.version")), "js")
  )
