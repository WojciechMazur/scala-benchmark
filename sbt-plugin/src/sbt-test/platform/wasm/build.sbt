import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.*
import org.scalajs.linker.interface.ESVersion

lazy val benchmarks = FixtureBuild
  .matrix("wasm")
  .settings(
    scalaJSLinkerConfig ~= (_.withESFeatures(_.withESVersion(ESVersion.ES2022).withUseWebAssembly(true)))
  )
lazy val root = project
  .in(file("."))
  .settings(
    FixtureBuild.settings(benchmarks.js(sys.props("benchmark.scala.version")), "wasm")
  )
