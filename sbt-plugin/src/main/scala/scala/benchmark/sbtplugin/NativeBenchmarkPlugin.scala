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

import scala.scalanative.build.Mode
import scala.scalanative.sbtplugin.{ScalaNativePlatform, ScalaNativePlugin, ScalaNativePluginInternal}
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport.*
import scala.sys.process.Process

import BenchmarkPlugin.autoImport.*
import pl.project13.scala.sbt.JmhPlugin
import pl.project13.scala.sbt.JmhPlugin.JmhKeys.*
import sbt.{*, given}
import sbt.Keys.*
import sbt.complete.DefaultParsers.spaceDelimited

/** Scala Native backend: the `Jmh` configuration links an optimized executable that hosts the derived harnesses. */
object NativeBenchmarkPlugin extends AutoPlugin:
  override def requires: Plugins = BenchmarkPlugin && ScalaNativePlugin
  override def trigger: PluginTrigger = allRequirements

  override def projectSettings: Seq[Setting[?]] =
    inConfig(Jmh)(ScalaNativePluginInternal.scalaNativeConfigSettings(false)) ++
      BenchmarkPlugin.withoutJmh(Def.setting(ScalaNativePlatform.current)) ++ Seq(
        // Benchmarks may live in the Jmh source directories; they are compiled with the main sources so that
        // the generated entry point, the only source of the Jmh configuration, can refer to them.
        Compile / unmanagedSourceDirectories ++= (Jmh / unmanagedSourceDirectories).value,
        Jmh / unmanagedSources := Nil,
        Jmh / classDirectory := crossTarget.value / "jmh-classes",
        Jmh / dependencyClasspath := Def.uncached((Compile / fullClasspath).value),
        Jmh / mainClass := Some(GeneratedSources.mainClass),
        Jmh / run / mainClass := (Jmh / mainClass).value,
        Jmh / nativeConfig ~= (_.withMode(Mode.releaseFull).withMultithreading(true)),
        Jmh / JmhPlugin.generateJmhSourcesAndResources := Def.uncached {
          BenchmarkPlugin.requireSupportedJmh("Scala Native", (Jmh / version).value)
          val config = (Jmh / nativeConfig).value
          val buildInfo = Seq(
            "scalaVersion" -> scalaVersion.value,
            "scalaNativeVersion" -> nativeVersion,
            "mode" -> config.mode.name,
            "lto" -> config.lto.name,
            "gc" -> config.gc.name,
            "compileOptions" -> config.compileOptions.mkString(" "),
            "linkingOptions" -> config.linkingOptions.mkString(" ")
          )
          val main = GeneratedSources.write(
            (Jmh / sourceManaged).value / "BenchmarkMain.scala",
            GeneratedSources.harnessMain(benchmarkClasses.value, buildInfo)
          )
          (main, Seq.empty[File])
        },
        Jmh / run := {
          val arguments = spaceDelimited("<JMH option>").parsed
          val executable = fileConverter.value.toPath((Jmh / nativeLink).value).toAbsolutePath.toString
          val exit = Process(executable +: arguments, baseDirectory.value, (Jmh / run / envVars).value.toSeq*).!
          if exit != 0 then sys.error(s"Benchmark executable exited with $exit")
        }
      )

end NativeBenchmarkPlugin
