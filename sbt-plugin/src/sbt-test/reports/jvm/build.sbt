lazy val benchmarks = FixtureBuild
  .matrix("jvm")
  .settings(
    Compile / unmanagedSources ~= (_.filter(_.getName == "ReportFormatsBenchmark.scala"))
  )
lazy val root = project
  .in(file("."))
  .settings(
    ReportChecks.settings(benchmarks.jvm(sys.props("benchmark.scala.version")), "jvm")
  )
