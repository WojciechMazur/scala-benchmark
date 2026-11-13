lazy val benchmarks = FixtureBuild
  .matrix("jvm")
  .settings(pl.project13.scala.sbt.JmhPlugin.JmhKeys.Jmh / javaOptions += "-Djmh.ignoreLock=true")
  .settings(
    Compile / sources := (Compile / sources).value
      .filter(file => Set("ExecutionSemanticsBenchmark.scala", "SharedLifecycleBenchmark.scala")(file.getName))
  )
lazy val root = project
  .in(file("."))
  .settings(
    ExecutionChecks.settings(benchmarks.jvm(sys.props("benchmark.scala.version")), "jvm")
  )
