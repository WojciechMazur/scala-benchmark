lazy val benchmarks = FixtureBuild.matrix("jvm", "native", "js") { platform =>
  FixtureBuild.fixtureDirectories("runner-failures") ++ FixtureBuild.only("RunnerFailureBenchmarks.scala") ++
    RunnerFailureChecks.settings(platform)
}

lazy val root = project.in(file(".")).settings(FixtureBuild.commands)
