import org.scalajs.linker.interface.ESVersion

lazy val benchmarks = FixtureBuild
  .matrix("wasm")
  .settings(
    scalaJSLinkerConfig ~= (_.withESFeatures(_.withESVersion(ESVersion.ES2022).withUseWebAssembly(true)))
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
    RunnerFailureChecks.settings(benchmarks.js(sys.props("benchmark.scala.version")), "wasm")
  )
