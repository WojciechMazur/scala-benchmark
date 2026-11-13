lazy val benchmarks = FixtureBuild
  .matrix("jvm")
  .settings(
    Compile / unmanagedSourceDirectories += file(
      sys.props("benchmark.scripted.support")
    ).getParentFile / "test/fixtures/output-format",
    Compile / sources := (Compile / sources).value.filter(f =>
      Set("OutputFormatEdgeBenchmarks.scala", "OutputFormatBenchmarks.scala", "Journal.scala")(f.getName)
    ),
    pl.project13.scala.sbt.JmhPlugin.JmhKeys.Jmh / javaOptions ++= Seq(
      "-Djmh.ignoreLock=true",
      "-Doutputformat.evidence=" + (file(
        sys.props("benchmark.scripted.support")
      ).getParentFile.getParentFile.getParentFile / "target/execution-parity/output-format/jvm").getAbsolutePath
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
