lazy val benchmarks = FixtureBuild
  .matrix("js")
  .settings(
    Compile / unmanagedSourceDirectories += file(
      sys.props("benchmark.scripted.support")
    ).getParentFile / "test/fixtures/output-factory",
    Compile / sources := (Compile / sources).value.filter(f =>
      f.getName == "OutputFactoryBenchmark.scala" || (f.getName == "Transcripts.scala" && f.getParentFile.getName == "js")
    )
  )
  .settings(OutputFactoryChecks.settings("js"))
