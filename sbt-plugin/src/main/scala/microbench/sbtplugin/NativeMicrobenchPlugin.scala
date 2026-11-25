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

import scala.scalanative.build.Mode
import scala.scalanative.sbtplugin.{ScalaNativePlatform, ScalaNativePlugin, ScalaNativePluginInternal}
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport.*

import pl.project13.scala.sbt.JmhPlugin.JmhKeys.*
import sbt.{*, given}
import sbt.Keys.*
import sbt.complete.DefaultParsers.spaceDelimited

/** Scala Native backend: the `Jmh` configuration links an optimized executable that hosts the derived harnesses. */
object NativeMicrobenchPlugin extends AutoPlugin:
  override def requires: Plugins = MicrobenchPlugin && ScalaNativePlugin
  override def trigger: PluginTrigger = allRequirements

  override def projectSettings: Seq[Setting[?]] =
    inConfig(Jmh)(ScalaNativePluginInternal.scalaNativeConfigSettings(false)) ++
      MicrobenchPlugin.harnessSettings("Scala Native", Def.setting(ScalaNativePlatform.current), buildInfo) ++ Seq(
        Jmh / nativeConfig ~= (_.withMode(Mode.releaseFull).withMultithreading(true)),
        Jmh / run := {
          val arguments = spaceDelimited("<JMH option>").parsed
          val executable = fileConverter.value.toPath((Jmh / nativeLink).value).toAbsolutePath.toString
          MicrobenchPlugin.launch(executable +: arguments, baseDirectory.value, (Jmh / run / envVars).value)
        }
      )

  private def buildInfo = Def.task {
    val config = (Jmh / nativeConfig).value
    Seq(
      "scalaVersion" -> scalaVersion.value,
      "scalaNativeVersion" -> nativeVersion,
      "mode" -> config.mode.name,
      "lto" -> config.lto.name,
      "gc" -> config.gc.name,
      "compileOptions" -> config.compileOptions.mkString(" "),
      "linkingOptions" -> config.linkingOptions.mkString(" ")
    )
  }
