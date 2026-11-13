import org.scalajs.linker.interface.ESVersion

lazy val benchmarks = FixtureBuild
  .matrix("wasm")
  .settings(
    scalaJSLinkerConfig ~= (_.withESFeatures(_.withESVersion(ESVersion.ES2022).withUseWebAssembly(true)))
  )
  .settings(
    Compile / sources := (Compile / sources).value
      .filter(file => file.getName == "AggregationEdgeBenchmark.scala")
  )
lazy val root = project
  .in(file("."))
  .settings(
    AggregationChecks.settings(benchmarks.js(sys.props("benchmark.scala.version")), "wasm")
  )
