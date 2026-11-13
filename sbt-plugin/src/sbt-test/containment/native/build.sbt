lazy val benchmarks = FixtureBuild
  .matrix("native")
  .settings(
    Compile / nativeConfig ~= (_.withLTO(scala.scalanative.build.LTO(sys.env.getOrElse("SCALANATIVE_LTO", "none"))))
  )
  .settings(
    Compile / unmanagedSourceDirectories ++= {
      val base = file(sys.props("benchmark.scripted.support")).getParentFile / "test/fixtures/containment"
      Seq(base / "shared", base / "native")
    },
    Compile / sources := (Compile / sources).value
      .filter(file =>
        Set(
          "ForkContainmentBenchmark.scala",
          "ProcessBenchmarks.scala",
          "ChildTreeBenchmark.scala",
          "ConsoleInventory.scala",
          "TreeHost.scala"
        )(file.getName)
      )
  )
lazy val root = project
  .in(file("."))
  .settings(
    ForkContainmentChecks.settings(benchmarks.native(sys.props("benchmark.scala.version")), "native")
  )
