lazy val benchmarks = FixtureBuild
  .matrix("native")
  .settings(
    Compile / nativeConfig ~= (_.withDump(true)
      // Scala Native 0.5.12 eh.cpp needs this explicit header with recent macOS SDKs.
      .withCppOptions(_ ++ Seq("-include", "cstdlib"))
      .withLTO(
        scala.scalanative.build
          .LTO(sys.env.getOrElse("SCALANATIVE_LTO", "none"))
      ))
  )
  .settings(Compile / sources := (Compile / sources).value.filter(_.getName == "IntegrityBenchmark.scala"))
lazy val root = project
  .in(file("."))
  .settings(IntegrityChecks.settings(benchmarks.native(sys.props("benchmark.scala.version")), "native"))
