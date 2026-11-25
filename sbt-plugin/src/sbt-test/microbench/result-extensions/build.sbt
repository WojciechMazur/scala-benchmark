lazy val benchmarks = FixtureBuild.matrix("jvm", "native", "js") { _ =>
  FixtureBuild.fixtureDirectories("result-extensions") ++ FixtureBuild.only("ResultExtensionBenchmark.scala")
}

lazy val root = project.in(file(".")).settings(FixtureBuild.commands)
