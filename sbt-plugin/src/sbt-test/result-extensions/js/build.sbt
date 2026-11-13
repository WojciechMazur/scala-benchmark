lazy val benchmarks = FixtureBuild
  .matrix("js")
  .settings(
    Compile / unmanagedSourceDirectories += file(
      sys.props("benchmark.scripted.support")
    ).getParentFile / "test/fixtures/result-extensions",
    Compile / sources := (Compile / sources).value.filter(_.getName == "ResultExtensionBenchmark.scala")
  )
