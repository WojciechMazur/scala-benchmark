import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.*
import org.scalajs.linker.interface.ESVersion

lazy val benchmarks = FixtureBuild
  .matrix("wasm")
  .settings(
    Compile / unmanagedSources ~= (_.filter(_.getName == "ReportFormatsBenchmark.scala")),
    scalaJSLinkerConfig ~= (_.withESFeatures(_.withESVersion(ESVersion.ES2022).withUseWebAssembly(true)))
  )
lazy val root = project
  .in(file("."))
  .settings(
    ReportChecks.settings(benchmarks.js(sys.props("benchmark.scala.version")), "wasm")
  )
