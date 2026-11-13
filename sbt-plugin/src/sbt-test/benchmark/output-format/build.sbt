import pl.project13.scala.sbt.JmhPlugin.JmhKeys.Jmh

lazy val benchmarks = FixtureBuild.matrix("jvm", "native", "js") { platform =>
  val formats = Seq("OutputFormatEdgeBenchmarks.scala", "OutputFormatBenchmarks.scala", "Journal.scala")
  val directories =
    FixtureBuild.fixtureDirectories("output-format", "output-format/" + FixtureBuild.host(platform))
  if (platform == "jvm")
    directories ++ FixtureBuild.only(formats*) ++ Seq(
      Jmh / javaOptions += "-Doutputformat.evidence=" +
        (FixtureBuild.evidence / "output-format/jvm").getAbsolutePath
    )
  else
    // The portable runtime is also checked through its observer API, with checks shared with the unit tests.
    directories ++ Seq(Compile / unmanagedSources += FixtureBuild.trialObserverChecks) ++
      FixtureBuild.only((formats ++ Seq("TrialObserverChecks.scala", "TrialObserverBenchmark.scala"))*)
}

lazy val root = project.in(file(".")).settings(FixtureBuild.commands)
