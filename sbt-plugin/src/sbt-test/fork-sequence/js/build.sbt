lazy val benchmarks = FixtureBuild
  .matrix("js")
  .settings(
    Compile / unmanagedSourceDirectories ++= {
      val fixtures = file(sys.props("benchmark.scripted.support")).getParentFile / "test/fixtures/fork-sequence"
      Seq(fixtures / "shared", fixtures / "js")
    },
    Compile / sources := (Compile / sources).value
      .filter(f => Set("ForkSequenceBenchmark.scala", "ForkJournal.scala")(f.getName))
  )
  .settings(
    benchmarkNodeOptions += "--import",
    benchmarkNodeOptions += (file(
      sys.props("benchmark.scripted.support")
    ).getParentFile / "test/fixtures/fork-sequence/journal-host.mjs").getAbsolutePath
  )
lazy val root = project
  .in(file("."))
  .settings(
    ForkSequenceChecks.settings(benchmarks.js(sys.props("benchmark.scala.version")), "js")
  )
