lazy val benchmarks = FixtureBuild
  .matrix("jvm")
  .settings(pl.project13.scala.sbt.JmhPlugin.JmhKeys.Jmh / javaOptions += "-Djmh.ignoreLock=true")
  .settings(
    Compile / sources := (Compile / sources).value
      .filter(file => Set("GroupFailureBenchmark.scala", "ProcessBenchmarks.scala")(file.getName))
  )
lazy val root = project
  .in(file("."))
  .settings(
    GroupFailureChecks.settings(benchmarks.jvm(sys.props("benchmark.scala.version")), "jvm")
  )
