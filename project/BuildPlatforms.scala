import sbt.{*, given}
import sbt.Keys.*
import BuildSettings.*
import scala.scalanative.sbtplugin.ScalaNativePlugin
import org.scalajs.sbtplugin.ScalaJSPlugin

object BuildPlatforms {
  extension (matrix: ProjectMatrix)
    def libraryPlatforms(
        jvmSettings: Seq[Setting[?]] = Nil,
        nativeSettings: Seq[Setting[?]] = Nil,
        jsSettings: Seq[Setting[?]] = Nil
    ): ProjectMatrix = matrix
      .defaultAxes()
      .settings(autoScalaLibrary := true, crossPaths := true)
      .customRow(true, Seq(VirtualAxis.jvm), (p: Project) => p.settings(futureLazyVals ++ jvmSettings))
      .customRow(
        true,
        Seq(VirtualAxis.native),
        (p: Project) => p.enablePlugins(ScalaNativePlugin).settings(nativeSettings)
      )
      .customRow(true, Seq(VirtualAxis.js), (p: Project) => p.enablePlugins(ScalaJSPlugin).settings(futureLazyVals ++ jsSettings))
}
