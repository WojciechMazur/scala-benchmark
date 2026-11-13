lazy val benchmarks = FixtureBuild
  .matrix("jvm")
  .settings(pl.project13.scala.sbt.JmhPlugin.JmhKeys.Jmh / javaOptions += "-Djmh.ignoreLock=true")
  .settings(
    Compile / unmanagedSourceDirectories += file(
      sys.props("benchmark.scripted.support")
    ).getParentFile / "test/fixtures/formatters",
    Compile / sources := (Compile / sources).value.filter(_.getName == "FormatterBenchmark.scala")
  )
  .settings(ResultFormatChecks.settings("jvm"))
