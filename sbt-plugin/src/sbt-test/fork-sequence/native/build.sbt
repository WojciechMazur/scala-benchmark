lazy val benchmarks = FixtureBuild
  .matrix("native")
  .settings(
    Compile / nativeConfig ~= (_.withLTO(scala.scalanative.build.LTO(sys.env.getOrElse("SCALANATIVE_LTO", "none"))))
  )
  .settings(
    Compile / unmanagedSourceDirectories ++= {
      val fixtures = file(sys.props("benchmark.scripted.support")).getParentFile / "test/fixtures/fork-sequence"
      Seq(fixtures / "shared", fixtures / "native")
    },
    Compile / sources := (Compile / sources).value
      .filter(f => Set("ForkSequenceBenchmark.scala", "ForkJournal.scala", "SerializationProbe.scala")(f.getName))
  )
lazy val root = project
  .in(file("."))
  .settings(
    ForkSequenceChecks.settings(benchmarks.native(sys.props("benchmark.scala.version")), "native")
  )
