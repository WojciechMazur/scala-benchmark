import org.scalajs.linker.interface.ESVersion

lazy val benchmarks = FixtureBuild
  .matrix("wasm")
  .settings(
    scalaJSLinkerConfig ~= (_.withESFeatures(_.withESVersion(ESVersion.ES2022).withUseWebAssembly(true)))
  )
  .settings(
    Compile / sources := (Compile / sources).value
      .filter(file => Set("ExecutionSemanticsBenchmark.scala", "SharedLifecycleBenchmark.scala")(file.getName))
  )
lazy val root = project
  .in(file("."))
  .settings(
    ExecutionChecks.settings(benchmarks.js(sys.props("benchmark.scala.version")), "wasm")
  )
