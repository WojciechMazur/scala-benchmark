lazy val benchmarks = FixtureBuild
  .matrix("js")
  .settings(
    Compile / unmanagedSourceDirectories ++= {
      val base = file(sys.props("benchmark.scripted.support")).getParentFile / "test/fixtures/containment"
      Seq(base / "shared", base / "js")
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
  .settings(
    benchmarkNodeOptions ++= Seq(
      "--import",
      (file(
        sys.props("benchmark.scripted.support")
      ).getParentFile / "test/fixtures/containment/tree-host.mjs").getAbsolutePath
    )
  )
lazy val root = project
  .in(file("."))
  .settings(
    ForkContainmentChecks.settings(benchmarks.js(sys.props("benchmark.scala.version")), "js")
  )
