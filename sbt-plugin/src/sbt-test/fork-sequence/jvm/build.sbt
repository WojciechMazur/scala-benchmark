lazy val benchmarks = FixtureBuild
  .matrix("jvm")
  .settings(pl.project13.scala.sbt.JmhPlugin.JmhKeys.Jmh / javaOptions += "-Djmh.ignoreLock=true")
  .settings(
    Compile / unmanagedSourceDirectories ++= {
      val fixtures = file(sys.props("benchmark.scripted.support")).getParentFile / "test/fixtures/fork-sequence"
      Seq(fixtures / "shared", fixtures / "jvm-native")
    },
    Compile / sources := (Compile / sources).value
      .filter(f => Set("ForkSequenceBenchmark.scala", "ForkJournal.scala")(f.getName))
  )
lazy val root = project
  .in(file("."))
  .settings(
    ForkSequenceChecks.settings(benchmarks.jvm(sys.props("benchmark.scala.version")), "jvm")
  )
