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
package microbench.runtime

/** Harnesses and build information of the benchmark project, installed by its generated entry point. */
object Registry:
  private var suites = Map.empty[String, Suite]
  private var build = Vector.empty[(String, String)]

  /** Installs the benchmark project. `buildInfo` from the build tool takes precedence over what the platform can
    * report about the running program itself.
    */
  def install(suites: Seq[(String, Suite)], buildInfo: Seq[(String, String)]): Unit =
    val duplicates = suites.groupBy(_._1).collect { case (name, entries) if entries.size > 1 => name }
    require(duplicates.isEmpty, "Benchmark classes listed more than once: " + duplicates.toVector.sorted.mkString(", "))
    this.suites = suites.toMap
    this.build = (Platform.defaultBuildInfo.toMap ++ buildInfo).toVector.sortBy(_._1)

  /** Entries of the public `microbench.run` entry point. */
  def entries(suites: Seq[microbench.BenchmarkSuite]): Seq[(String, Suite)] =
    suites.map(suite => suite.name -> suite.harness)

  /** Benchmark class names, in a stable order. */
  def names: Vector[String] = suites.keys.toVector.sorted

  def suite(name: String): Option[Suite] = suites.get(name)

  def buildInfo: Vector[(String, String)] = build
