lazy val benchmarks = FixtureBuild
  .matrix("js")
  .settings(
    Compile / sources := (Compile / sources).value
      .filter(file => Set("ExecutionSemanticsBenchmark.scala", "SharedLifecycleBenchmark.scala")(file.getName))
  )
lazy val root = project
  .in(file("."))
  .settings(
    ExecutionChecks.settings(benchmarks.js(sys.props("benchmark.scala.version")), "js")
  )
