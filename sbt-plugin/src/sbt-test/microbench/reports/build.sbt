lazy val benchmarks = FixtureBuild.matrix("jvm", "native", "js") { platform =>
  FixtureBuild.only("ReportFormatsBenchmark.scala") ++ ReportChecks.settings(platform)
}

lazy val root = project.in(file(".")).settings(FixtureBuild.commands)
