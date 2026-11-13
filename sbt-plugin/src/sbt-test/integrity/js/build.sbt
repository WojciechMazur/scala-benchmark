lazy val benchmarks = FixtureBuild
  .matrix("js")
  .settings(
    benchmarkNodeOptions := Seq(
      "--import",
      (file(sys.props("benchmark.scripted.support")).getParentFile / "test/integrity/observe-host.mjs").getAbsolutePath
    )
  )
  .settings(Compile / sources := (Compile / sources).value.filter(_.getName == "IntegrityBenchmark.scala"))
lazy val root =
  project.in(file(".")).settings(IntegrityChecks.settings(benchmarks.js(sys.props("benchmark.scala.version")), "js"))
