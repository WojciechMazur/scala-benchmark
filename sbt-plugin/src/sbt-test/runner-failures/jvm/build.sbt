lazy val benchmarks = FixtureBuild
  .matrix("jvm")
  .settings(pl.project13.scala.sbt.JmhPlugin.JmhKeys.Jmh / javaOptions += "-Djmh.ignoreLock=true")
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
    RunnerFailureChecks.settings(benchmarks.jvm(sys.props("benchmark.scala.version")), "jvm")
  )
