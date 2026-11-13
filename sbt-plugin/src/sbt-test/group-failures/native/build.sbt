lazy val benchmarks = FixtureBuild
  .matrix("native")
  .settings(
    Compile / nativeConfig ~= (_.withLTO(scala.scalanative.build.LTO(sys.env.getOrElse("SCALANATIVE_LTO", "none"))))
  )
  .settings(
    Compile / sources := (Compile / sources).value
      .filter(file => Set("GroupFailureBenchmark.scala", "ProcessBenchmarks.scala")(file.getName))
  )
lazy val root = project
  .in(file("."))
  .settings(
    GroupFailureChecks.settings(benchmarks.native(sys.props("benchmark.scala.version")), "native")
  )
