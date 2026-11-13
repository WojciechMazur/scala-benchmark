lazy val benchmarks = FixtureBuild
  .matrix("jvm")
  .settings(pl.project13.scala.sbt.JmhPlugin.JmhKeys.Jmh / javaOptions += "-Djmh.ignoreLock=true")
  .settings(
    Compile / unmanagedSourceDirectories += file(
      sys.props("benchmark.scripted.support")
    ).getParentFile / "test/fixtures/output-factory",
    Compile / sources := (Compile / sources).value.filter(f =>
      f.getName == "OutputFactoryBenchmark.scala" || (f.getName == "Transcripts.scala" && f.getParentFile.getName == "process")
    )
  )
  .settings(OutputFactoryChecks.settings("jvm"))
