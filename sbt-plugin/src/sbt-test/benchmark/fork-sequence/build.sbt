lazy val benchmarks = FixtureBuild.matrix("jvm", "native", "js") { platform =>
  val (directory, extraSources) = platform match {
    case "jvm"    => ("jvm-native", Nil)
    case "native" => ("native", Seq("SerializationProbe.scala"))
    case _        => ("js", Nil)
  }
  val sources = FixtureBuild.fixtureDirectories("fork-sequence/shared", "fork-sequence/" + directory) ++
    FixtureBuild.only((Seq("ForkSequenceBenchmark.scala", "ForkJournal.scala") ++ extraSources) *)
  val host =
    if (platform == "js") FixtureBuild.nodeImport(FixtureBuild.fixtures / "fork-sequence/journal-host.mjs") else Nil
  sources ++ host ++ ForkSequenceChecks.settings(platform)
}

lazy val root = project.in(file(".")).settings(FixtureBuild.commands)
