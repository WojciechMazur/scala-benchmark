lazy val benchmarks = FixtureBuild
  .matrix("jvm")
  .settings(pl.project13.scala.sbt.JmhPlugin.JmhKeys.Jmh / javaOptions += "-Djmh.ignoreLock=true")
  .settings(
    Compile / unmanagedSourceDirectories += file(
      sys.props("benchmark.scripted.support")
    ).getParentFile / "test/fixtures/stdio",
    Compile / sources := (Compile / sources).value.filter(f =>
      f.getName == "StdioBenchmark.scala" || (f.getName == "Transcripts.scala" && f.getParentFile.getName == "process")
    )
  )
  .settings(StdioChecks.settings("jvm"))
