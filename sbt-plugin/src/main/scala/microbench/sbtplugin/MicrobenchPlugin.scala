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
package microbench.sbtplugin

import pl.project13.scala.sbt.JmhPlugin
import pl.project13.scala.sbt.JmhPlugin.JmhKeys.*
import sbt.{*, given}
import sbt.Keys.*
import sbt.complete.DefaultParsers.spaceDelimited

/** Shared keys, and the JVM backend: upstream JMH generates and runs the benchmarks.
  *
  * The only additions on the JVM are platform eligibility (`@Platforms`) and the HTML and LaTeX report formats.
  * [[NativeMicrobenchPlugin]] and [[ScalaJSMicrobenchPlugin]] replace generation and execution for their platforms.
  */
object MicrobenchPlugin extends AutoPlugin:
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

  /** Native and Scala.js backends implement the JMH API themselves. Their `Jmh` configuration only compiles the
    * generated entry point, which hosts the harnesses derived for the benchmarks of the main sources.
    */
  private[sbtplugin] def harnessSettings(
      backend: String,
      platform: Def.Initialize[String],
      buildInfo: Def.Initialize[Task[Seq[(String, String)]]]
  ): Seq[Setting[?]] = Seq(
    libraryDependencies ~= (_.filterNot(_.organization == jmhOrganization)),
    // Benchmarks may live in the Jmh source directories; they are compiled with the main sources so that the
    // generated entry point can refer to them.
    Compile / unmanagedSourceDirectories ++= (Jmh / unmanagedSourceDirectories).value,
    Jmh / unmanagedSources := Nil,
    Jmh / classDirectory := crossTarget.value / "jmh-classes",
    Jmh / dependencyClasspath := Def.uncached((Compile / fullClasspath).value),
    Jmh / mainClass := Some(GeneratedSources.mainClass),
    Jmh / run / mainClass := (Jmh / mainClass).value,
    Jmh / JmhPlugin.generateJmhSourcesAndResources := Def.uncached {
      val supported = BuildInfo.jmhVersion
      require((Jmh / version).value == supported, s"The $backend backend supports JMH API $supported")
      val main = GeneratedSources.write(
        (Jmh / sourceManaged).value / "BenchmarkMain.scala",
        GeneratedSources.harnessMain(benchmarkClasses.value, buildInfo.value)
      )
      (main, Seq.empty[File])
    }
  )

  /** Forked JMH output. A forked JVM writes to the sbt server's own stdout by default, which the sbt 2 thin client
    * never shows, so listings and results would be lost. Each write goes to the current `System.out`, which sbt
    * routes to the terminal of the client that issued the command; closing the fork's stream must not close it.
    */
  private val forkedOutput: OutputStrategy = OutputStrategy.CustomOutput(new java.io.OutputStream:
    override def write(byte: Int): Unit = System.out.write(byte)
    override def write(bytes: Array[Byte], offset: Int, length: Int): Unit = System.out.write(bytes, offset, length)
    override def flush(): Unit = System.out.flush()
    override def close(): Unit = flush())

  /** Runs a benchmark launcher in the project directory, failing the task when it fails. */
  private[sbtplugin] def launch(command: Seq[String], directory: File, environment: Map[String, String]): Unit =
    val exit = scala.sys.process.Process(command, directory, environment.toSeq*).!
    if exit != 0 then sys.error(s"Benchmark process exited with $exit")

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
    // The same dependency on every platform; on the JVM, upstream JMH still runs the benchmarks.
    libraryDependencies ++= {
      if benchmarkUsePublishedArtifacts.value then
        Seq((organization %% "microbench" % BuildInfo.version).platform(platform.value))
      else Nil
    },
    Jmh / run / mainClass := Some("microbench.internal.JvmMain"),
    Jmh / run / outputStrategy := Some(forkedOutput),
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
      new ForkRun(ForkOptions().withOutputStrategy(OutputStrategy.LoggedOutput(streams.value.log)))
        .run(
          "microbench.internal.JvmGenerator",
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
          IO.withTemporaryFile("microbench-jmh-", ".json") { json =>
            jmh.run(main, classpath, report.jmhArguments(json), log).get
            IO.write(report.output, JvmReport.render(json, report.format))
            log.info("Benchmark report: " + report.output.getAbsolutePath)
          }
    }
  )

end MicrobenchPlugin
