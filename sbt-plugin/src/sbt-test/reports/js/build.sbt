lazy val benchmarks = FixtureBuild
  .matrix("js")
  .settings(
    Compile / unmanagedSources ~= (_.filter(_.getName == "ReportFormatsBenchmark.scala"))
  )
lazy val root = project
  .in(file("."))
  .settings(
    ReportChecks.settings(benchmarks.js(sys.props("benchmark.scala.version")), "js")
  )
