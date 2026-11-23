lazy val benchmarks = FixtureBuild.matrix("jvm", "native") { platform =>
  FixtureBuild.only("GroupFailureBenchmark.scala", "ProcessBenchmarks.scala") ++ GroupFailureChecks.settings(platform)
}

lazy val root = project.in(file(".")).settings(FixtureBuild.commands)
