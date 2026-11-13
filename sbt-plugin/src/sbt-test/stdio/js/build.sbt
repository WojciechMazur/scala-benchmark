lazy val benchmarks = FixtureBuild
  .matrix("js")
  .settings(
    Compile / unmanagedSourceDirectories += file(
      sys.props("benchmark.scripted.support")
    ).getParentFile / "test/fixtures/stdio",
    Compile / sources := (Compile / sources).value.filter(f =>
      f.getName == "StdioBenchmark.scala" || (f.getName == "Transcripts.scala" && f.getParentFile.getName == "js")
    )
  )
  .settings(StdioChecks.settings("js"))
