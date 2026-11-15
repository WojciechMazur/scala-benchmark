/*
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  The copyright holders
 * designate this particular file as subject to the "Classpath" exception
 * as provided in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package scala.benchmark.sbtplugin

import pl.project13.scala.sbt.JmhPlugin
import pl.project13.scala.sbt.JmhPlugin.JmhKeys.*
import sbt.{*, given}
import sbt.Keys.*
import sbt.complete.DefaultParsers.spaceDelimited

/** Shared keys, and the JVM backend: upstream JMH generates and runs the benchmarks.
  *
  * The only additions on the JVM are platform eligibility (`@Platforms`) and the HTML and LaTeX report formats.
  * [[NativeBenchmarkPlugin]] and [[ScalaJSBenchmarkPlugin]] replace generation and execution for their platforms.
  */
object BenchmarkPlugin extends AutoPlugin:
  override def requires: Plugins = JmhPlugin
  override def trigger: PluginTrigger = allRequirements

  object autoImport:
    val benchmarkUsePublishedArtifacts = settingKey[Boolean]("Resolve the published benchmark interface and runtime")
    val benchmarkClasses = taskKey[Seq[String]]("Classes declaring JMH benchmarks, found in the compilation analysis")

  import autoImport.*

  /** Compiles the generated platform inventory between the user's sources and JMH's generated ones. */
  private val Inventory = config("benchmark-inventory").hide

  override def projectConfigurations: Seq[Configuration] = Seq(Inventory)

  private val organization = BuildInfo.organization
  private val jmhOrganization = "org.openjdk.jmh"
  private val supportedJmh = "1.37"

  /** Non-JVM backends implement the JMH API themselves. */
  private[sbtplugin] def withoutJmh(platform: Def.Initialize[String]): Seq[Setting[?]] = Seq(
    libraryDependencies ~= (_.filterNot(_.organization == jmhOrganization)),
    libraryDependencies ++= {
      if benchmarkUsePublishedArtifacts.value then
        Seq((organization %% "benchmark-runtime" % BuildInfo.version).platform(platform.value))
      else Nil
    }
  )

  private[sbtplugin] def requireSupportedJmh(backend: String, version: String): Unit =
    require(version == supportedJmh, s"The $backend backend supports JMH API $supportedJmh")

  private val inventorySettings: Seq[Setting[?]] = inConfig(Inventory)(
    Defaults.configSettings ++ Seq(
      dependencyClasspath := Def.uncached((Compile / fullClasspath).value),
      sourceGenerators := Seq(Def.task {
        GeneratedSources.write(
          sourceManaged.value / "BenchmarkInventory.scala",
          GeneratedSources.inventory(benchmarkClasses.value)
        )
      }.taskValue),
      SettingKey[Boolean]("bspEnabled") := false
    )
  )

  override def projectSettings: Seq[Setting[?]] = inventorySettings ++ Seq(
    benchmarkUsePublishedArtifacts := true,
    benchmarkClasses := Def.uncached {
      GeneratedSources.benchmarkClasses((Compile / compile).value, streams.value.log)
    },
    libraryDependencies ++= {
      if platform.value == Platform.jvm && benchmarkUsePublishedArtifacts.value then
        Seq(organization %% "benchmark-interface" % BuildInfo.version)
      else Nil
    },
    Jmh / run / mainClass := Some("scala.benchmark.internal.JvmMain"),
    Jmh / dependencyClasspath ++= Def.uncached((Inventory / exportedProducts).value),
    Jmh / JmhPlugin.generateJmhSourcesAndResources := Def.uncached {
      require(
        (Jmh / generatorType).value != "asm",
        "Platform eligibility requires the reflection JMH generator"
      )
      val converter = fileConverter.value
      val bytecode = (Jmh / classDirectory).value.toPath
      val sources = (Jmh / sourceManaged).value
      val resources = (Jmh / resourceManaged).value
      val classpath = bytecode +: (Jmh / dependencyClasspath).value.map(entry => converter.toPath(entry.data))
      // Regenerate everything: removals and eligibility edits must not leave stale BenchmarkList entries.
      IO.delete(Seq(sources, resources))
      IO.createDirectories(Seq(sources, resources))
      new ForkRun(ForkOptions())
        .run(
          "scala.benchmark.internal.JvmGenerator",
          classpath,
          Seq(bytecode.toString, sources.toString, resources.toString),
          streams.value.log
        )
        .get
      ((sources ** "*.java").get(), (resources ** "*").get().filter(_.isFile))
    },
    Jmh / run := {
      val arguments = spaceDelimited("<JMH option>").parsed
      val converter = fileConverter.value
      val classpath = (Jmh / fullClasspath).value.map(entry => converter.toPath(entry.data))
      val main = (Jmh / run / mainClass).value.getOrElse(sys.error("No JMH main class"))
      val jmh = (Jmh / run / runner).value
      val workingDirectory = (Jmh / run / forkOptions).value.workingDirectory.getOrElse(file(sys.props("user.dir")))
      val log = streams.value.log
      JvmReport.Request(arguments, workingDirectory) match
        case None         => jmh.run(main, classpath, arguments, log).get
        case Some(report) =>
          IO.withTemporaryFile("scala-benchmark-jmh-", ".json") { json =>
            jmh.run(main, classpath, report.jmhArguments(json), log).get
            IO.write(report.output, JvmReport.render(json, report.format))
            log.info("Benchmark report: " + report.output.getAbsolutePath)
          }
    }
  )

end BenchmarkPlugin
