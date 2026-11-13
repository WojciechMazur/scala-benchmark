lazy val benchmarks = FixtureBuild
  .matrix("native")
  .settings(
    Compile / unmanagedSources += file(
      sys.props("benchmark.scripted.support")
    ).getParentFile.getParentFile.getParentFile /
      "runtime/shared/src/test/scala/scala/scalanative/jmh/TrialObserverChecks.scala",
    Compile / unmanagedSourceDirectories += file(sys.props("benchmark.scripted.support")).getParentFile /
      "test/fixtures/output-format",
    Compile / sources := (Compile / sources).value
      .filter(f =>
        Set(
          "TrialObserverChecks.scala",
          "TrialObserverBenchmark.scala",
          "OutputFormatEdgeBenchmarks.scala",
          "OutputFormatBenchmarks.scala",
          "Journal.scala"
        )(f.getName)
      )
  )
  .settings(
    Compile / unmanagedSourceDirectories += file(
      sys.props("benchmark.scripted.support")
    ).getParentFile / "test/fixtures/output-format/process"
  )
  .settings(
    Compile / sources := (Compile / sources).value
      .filter(f => f.getName != "Journal.scala" || f.getParentFile.getName == "process")
  )
