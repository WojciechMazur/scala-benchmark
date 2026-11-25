lazy val benchmarks = FixtureBuild.matrix("jvm", "native", "js") { platform =>
  FixtureBuild.only("ExecutionSemanticsBenchmark.scala", "SharedLifecycleBenchmark.scala") ++
    ExecutionChecks.settings(platform)
}

lazy val root = project.in(file(".")).settings(FixtureBuild.commands)
