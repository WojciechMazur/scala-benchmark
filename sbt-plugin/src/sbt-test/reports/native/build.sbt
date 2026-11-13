lazy val benchmarks = FixtureBuild
  .matrix("native")
  .settings(
    Compile / unmanagedSources ~= (_.filter(_.getName == "ReportFormatsBenchmark.scala"))
  )
lazy val root = project
  .in(file("."))
  .settings(
    ReportChecks.settings(benchmarks.native(sys.props("benchmark.scala.version")), "native")
  )
