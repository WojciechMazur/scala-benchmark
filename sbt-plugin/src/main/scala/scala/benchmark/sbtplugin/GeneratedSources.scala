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

import scala.benchmark.internal.reporting.JsonString

import sbt.*
import sbt.testing.AnnotatedFingerprint
import xsbti.compile.CompileAnalysis

/** Sources generated into benchmark projects, compiled after the user's own sources.
  *
  * Benchmark classes are found in the compilation analysis and then handed to macros, which derive a harness
  * (Native, Scala.js) or the platform inventory (JVM) for each class.
  */
private[sbtplugin] object GeneratedSources:
  private val packageName = "scala.benchmark.generated"
  val mainClass: String = packageName + ".BenchmarkMain"

  private object BenchmarkFingerprint extends AnnotatedFingerprint:
    def isModule: Boolean = false
    def annotationName: String = "org.openjdk.jmh.annotations.Benchmark"

  /** Concrete classes that declare or inherit a `@Benchmark` method. Like JMH, the default package is unsupported. */
  def benchmarkClasses(analysis: CompileAnalysis, log: Logger): Seq[String] =
    val (definitions, _) = Tests.discover(Seq(BenchmarkFingerprint), Tests.allDefs(analysis), log)
    val (named, unnamed) = definitions.map(_.name).distinct.sorted.partition(_.contains('.'))
    unnamed.foreach(name => log.warn(s"Benchmark class $name is ignored: benchmarks need a named package"))
    named

  private def typeName(name: String): String =
    name
      .split('.')
      .map(segment => if segment.matches("[A-Za-z_][A-Za-z0-9_]*") then segment else s"`$segment`")
      .mkString("_root_.", ".", "")

  private def entries(lines: Seq[String]): String = lines.map("    " + _).mkString("\n", ",\n", "\n  ")

  /** Entry point of a Native or Scala.js benchmark executable. */
  def harnessMain(classes: Seq[String], buildInfo: Seq[(String, String)]): String =
    val runtime = "scala.benchmark.runtime"
    // The same derivation as the public `scala.benchmark.run(args)(suite[...])` entry point.
    val suites = classes.map(name => s"scala.benchmark.suite[${typeName(name)}]")
    val info = buildInfo.sorted.map { case (key, value) => s"${JsonString.quote(key)} -> ${JsonString.quote(value)}" }
    s"""package $packageName
       |
       |object BenchmarkMain {
       |  def main(args: Array[String]): Unit = $runtime.Main.run(args, $runtime.Registry.entries(suites), buildInfo)
       |
       |  private def suites: Seq[scala.benchmark.BenchmarkSuite] = Seq(${entries(suites)})
       |
       |  private def buildInfo: Seq[(String, String)] = Seq(${entries(info)})
       |}
       |""".stripMargin

  /** Platform eligibility of JVM benchmarks, read before JMH generates its own harnesses and again at run time. */
  def inventory(classes: Seq[String]): String =
    val internal = "scala.benchmark.internal"
    val inventories = classes.map(name => s"$internal.Inventory.of[${typeName(name)}]")
    s"""package $packageName
       |
       |object BenchmarkInventory extends $internal.InventoryProvider {
       |  def entries: Array[$internal.InventoryEntry] =
       |    Seq[Array[$internal.InventoryEntry]](${entries(inventories)}).flatten.toArray
       |}
       |""".stripMargin

  /** Leaves an unchanged file untouched, so that it does not invalidate incremental compilation. */
  def write(file: File, text: String): Seq[File] =
    if !file.exists() || IO.read(file) != text then IO.write(file, text)
    Seq(file)

end GeneratedSources
