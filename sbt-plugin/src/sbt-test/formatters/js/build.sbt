lazy val benchmarks = FixtureBuild
  .matrix("js")
  .settings(
    Compile / unmanagedSourceDirectories += file(
      sys.props("benchmark.scripted.support")
    ).getParentFile / "test/fixtures/formatters",
    Compile / sources := (Compile / sources).value.filter(_.getName == "FormatterBenchmark.scala")
  )
  .settings(ResultFormatChecks.settings("js"))
