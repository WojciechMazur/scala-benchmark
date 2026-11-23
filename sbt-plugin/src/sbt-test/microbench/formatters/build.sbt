lazy val benchmarks = FixtureBuild.matrix("jvm", "native", "js") { platform =>
  FixtureBuild.fixtureDirectories("formatters") ++ FixtureBuild.only("FormatterBenchmark.scala") ++
    ResultFormatChecks.settings(platform)
}

lazy val root = project.in(file(".")).settings(FixtureBuild.commands)
