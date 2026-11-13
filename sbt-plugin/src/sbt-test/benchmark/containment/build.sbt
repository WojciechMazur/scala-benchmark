lazy val benchmarks = FixtureBuild.matrix("native", "js") { platform =>
  val sources = FixtureBuild.fixtureDirectories("containment/shared", "containment/" + platform) ++
    FixtureBuild.only(
      "ForkContainmentBenchmark.scala",
      "ProcessBenchmarks.scala",
      "ChildTreeBenchmark.scala",
      "ConsoleInventory.scala",
      "TreeHost.scala"
    )
  val host =
    if (platform == "js") FixtureBuild.nodeImport(FixtureBuild.fixtures / "containment/tree-host.mjs") else Nil
  sources ++ host ++ ForkContainmentChecks.settings(platform)
}

lazy val root = project.in(file(".")).settings(FixtureBuild.commands)
