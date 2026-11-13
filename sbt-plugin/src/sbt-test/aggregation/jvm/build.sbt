lazy val benchmarks = FixtureBuild
  .matrix("jvm")
  .settings(pl.project13.scala.sbt.JmhPlugin.JmhKeys.Jmh / javaOptions += "-Djmh.ignoreLock=true")
  .settings(
    Compile / sources := (Compile / sources).value
      .filter(file => file.getName == "AggregationEdgeBenchmark.scala")
  )
lazy val root = project
  .in(file("."))
  .settings(
    AggregationChecks.settings(benchmarks.jvm(sys.props("benchmark.scala.version")), "jvm")
  )
