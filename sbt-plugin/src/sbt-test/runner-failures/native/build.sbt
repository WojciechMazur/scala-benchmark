lazy val benchmarks = FixtureBuild
  .matrix("native")
  .settings(
    Compile / nativeConfig ~= (_.withLTO(scala.scalanative.build.LTO(sys.env.getOrElse("SCALANATIVE_LTO", "none"))))
  )
  .settings(
    Compile / unmanagedSourceDirectories += file(
      sys.props("benchmark.scripted.support")
    ).getParentFile / "test/fixtures/runner-failures",
    Compile / sources := (Compile / sources).value
      .filter(file => file.getName == "RunnerFailureBenchmarks.scala")
  )
lazy val root = project
  .in(file("."))
  .settings(
    RunnerFailureChecks.settings(benchmarks.native(sys.props("benchmark.scala.version")), "native")
  )
