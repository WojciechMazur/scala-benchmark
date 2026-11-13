lazy val benchmarks = FixtureBuild
  .matrix("native")
  .settings(
    Compile / nativeConfig ~= (_.withLTO(scala.scalanative.build.LTO(sys.env.getOrElse("SCALANATIVE_LTO", "none"))))
  )
  .settings(
    Compile / sources := (Compile / sources).value
      .filter(file => Set("ExecutionSemanticsBenchmark.scala", "SharedLifecycleBenchmark.scala")(file.getName))
  )
lazy val root = project
  .in(file("."))
  .settings(
    ExecutionChecks.settings(benchmarks.native(sys.props("benchmark.scala.version")), "native")
  )
