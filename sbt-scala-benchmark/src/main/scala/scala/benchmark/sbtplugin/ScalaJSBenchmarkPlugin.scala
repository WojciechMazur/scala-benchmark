package scala.benchmark.sbtplugin

import sbt.{*, given}
import sbt.Keys.*
import sbt.complete.DefaultParsers.spaceDelimited
import org.scalajs.sbtplugin.ScalaJSPlugin
import pl.project13.scala.sbt.JmhPlugin
import pl.project13.scala.sbt.JmhPlugin.JmhKeys.*

/** Reuses sbt-jmh's configuration without loading its JVM harness on Scala.js. */
object ScalaJSBenchmarkPlugin extends AutoPlugin {
  override def requires: Plugins = ScalaJSPlugin && JmhPlugin
  override def trigger: PluginTrigger = allRequirements

  private def unavailable(): Nothing = sys.error(
    "Scala.js benchmark execution is not implemented yet. " +
      "The portable core and annotation API are available; use JVM or Native for measurements."
  )

  override def projectSettings: Seq[Setting[?]] = Seq(
    libraryDependencies ~= (_.filterNot(_.organization == "org.openjdk.jmh")),
    Jmh / sourceGenerators := Nil,
    Jmh / resourceGenerators := Nil,
    Jmh / compile := Def.uncached { unavailable() },
    Jmh / JmhPlugin.generateJmhSourcesAndResources := Def.uncached { unavailable() },
    Jmh / run := {
      spaceDelimited("<JMH option>").parsed
      unavailable()
    }
  )
}
