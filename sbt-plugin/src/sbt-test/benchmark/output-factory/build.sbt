lazy val benchmarks = FixtureBuild.matrix("jvm", "native", "js") { platform =>
  FixtureBuild.fixtureDirectories("output-factory", "output-factory/" + FixtureBuild.host(platform)) ++
    FixtureBuild.only("OutputFactoryBenchmark.scala", "Transcripts.scala") ++ OutputFactoryChecks.settings(platform)
}

lazy val root = project.in(file(".")).settings(FixtureBuild.commands)
