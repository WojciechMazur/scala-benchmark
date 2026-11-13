lazy val benchmarks = FixtureBuild.matrix("jvm", "native", "js") { platform =>
  FixtureBuild.fixtureDirectories("stdio", "stdio/" + FixtureBuild.host(platform)) ++
    FixtureBuild.only("StdioBenchmark.scala", "Transcripts.scala") ++ StdioChecks.settings(platform)
}

lazy val root = project.in(file(".")).settings(FixtureBuild.commands)
