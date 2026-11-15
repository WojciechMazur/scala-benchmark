/*
 * Copyright (c) 2026, Wojciech Mazur.
 *
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

import scala.sys.process.Process

import BenchmarkPlugin.autoImport.*
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.*
import pl.project13.scala.sbt.JmhPlugin
import pl.project13.scala.sbt.JmhPlugin.JmhKeys.*
import sbt.{*, given}
import sbt.Keys.*
import sbt.complete.DefaultParsers.spaceDelimited

/** Scala.js backend: Node.js hosts the optimized JS or Wasm module, with a fresh process for every fork. */
object ScalaJSBenchmarkPlugin extends AutoPlugin:
  override def requires: Plugins = BenchmarkPlugin && ScalaJSPlugin
  override def trigger: PluginTrigger = allRequirements

  object autoImport:
    val benchmarkNode = settingKey[String]("Node.js executable used for benchmarks")
    val benchmarkNodeOptions = settingKey[Seq[String]]("Node.js options inherited by benchmark forks")

  import autoImport.*

  private val hostDirectory = "scala-benchmark"
  private val host = "node-host.mjs"

  private val hostModules =
    Seq(host, "progress-deadline.mjs", "worker-lifeline.mjs", "output-relay.mjs", "console-writer.mjs")

  /** The host is plain JavaScript shipped with the plugin; Node.js needs it on disk. */
  private def installHost(directory: File): File =
    hostModules.foreach { name =>
      val stream = getClass.getResourceAsStream(s"/$hostDirectory/$name")
      require(stream != null, "Missing benchmark Node resource: " + name)
      try IO.write(directory / name, IO.readStream(stream))
      finally stream.close()
    }
    directory / host

  override def projectSettings: Seq[Setting[?]] =
    inConfig(Jmh)(ScalaJSPlugin.compileConfigSettings) ++ BenchmarkPlugin.withoutJmh(platform) ++ Seq(
      benchmarkNode := "node",
      benchmarkNodeOptions := Nil,
      // See NativeBenchmarkPlugin: the Jmh configuration only compiles the generated entry point.
      Compile / unmanagedSourceDirectories ++= (Jmh / unmanagedSourceDirectories).value,
      Jmh / unmanagedSources := Nil,
      Jmh / classDirectory := crossTarget.value / "jmh-classes",
      Jmh / dependencyClasspath := Def.uncached((Compile / fullClasspath).value),
      Jmh / mainClass := Some(GeneratedSources.mainClass),
      Jmh / run / mainClass := (Jmh / mainClass).value,
      Jmh / scalaJSUseMainModuleInitializer := true,
      Jmh / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
      Jmh / JmhPlugin.generateJmhSourcesAndResources := Def.uncached {
        BenchmarkPlugin.requireSupportedJmh("Scala.js", (Jmh / version).value)
        val wasm = (Jmh / scalaJSLinkerConfig).value.esFeatures.useWebAssembly
        val buildInfo = Seq(
          "scalaVersion" -> scalaVersion.value,
          "scalaJSVersion" -> scalaJSVersion,
          "backend" -> (if wasm then "wasm" else "js"),
          "optimization" -> "full"
        )
        val main = GeneratedSources.write(
          (Jmh / sourceManaged).value / "BenchmarkMain.scala",
          GeneratedSources.harnessMain(benchmarkClasses.value, buildInfo)
        )
        (main, Seq.empty[File])
      },
      Jmh / run := {
        val arguments = spaceDelimited("<JMH option>").parsed
        val modules = (Jmh / fullLinkJS).value.data.publicModules.toVector
        require(modules.size == 1, "Benchmark linking must produce one public module")
        val module = (Jmh / fullLinkJS / scalaJSLinkerOutputDirectory).value / modules.head.jsFileName
        val launcher = installHost(target.value / hostDirectory)
        val nodeOptions = benchmarkNodeOptions.value
        val collects = arguments.sliding(2).exists(_ == Seq("-gc", "true"))
        val exposeGC = if collects && !nodeOptions.contains("--expose-gc") then Seq("--expose-gc") else Nil
        val command = Seq(benchmarkNode.value) ++ nodeOptions ++ exposeGC ++
          Seq(launcher.getAbsolutePath, module.getAbsolutePath) ++ arguments
        val exit = Process(command, baseDirectory.value, (Jmh / run / envVars).value.toSeq*).!
        if exit != 0 then sys.error(s"Scala.js benchmark exited with $exit")
      }
    )

end ScalaJSBenchmarkPlugin
