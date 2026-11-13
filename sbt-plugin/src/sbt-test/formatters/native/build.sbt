lazy val benchmarks = FixtureBuild
  .matrix("native")
  .settings(
    Compile / unmanagedSourceDirectories += file(
      sys.props("benchmark.scripted.support")
    ).getParentFile / "test/fixtures/formatters",
    Compile / sources := (Compile / sources).value.filter(_.getName == "FormatterBenchmark.scala")
  )
  .settings(ResultFormatChecks.settings("native"))
