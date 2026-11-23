lazy val benchmarks = FixtureBuild.matrix("jvm", "native", "js") { platform =>
  FixtureBuild.only("AggregationEdgeBenchmark.scala") ++ AggregationChecks.settings(platform)
}

lazy val root = project.in(file(".")).settings(FixtureBuild.commands)
