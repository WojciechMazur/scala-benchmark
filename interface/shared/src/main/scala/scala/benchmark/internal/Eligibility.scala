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
package scala.benchmark.internal

import scala.benchmark.Platform

/** Implemented by the generated inventory of a JVM benchmark project. Entries are constants: no benchmark class is
  * initialized to produce them.
  */
trait InventoryProvider:
  def entries: Array[InventoryEntry]

object InventoryProvider:

  /** Name of the object the sbt plugin generates into benchmark projects. */
  final val GeneratedName = "scala.benchmark.generated.BenchmarkInventory"

final case class Eligibility(
    allowed: Vector[Platform] = Platform.values.toVector,
    reasons: Vector[String] = Vector.empty
):
  require(allowed.nonEmpty, "Benchmark platform eligibility must not be empty")
  def supports(platform: Platform): Boolean = allowed.contains(platform)

  def description: String =
    s"allowed platforms: ${allowed.mkString(", ")}" +
      (if reasons.isEmpty then "" else s"; ${reasons.mkString("; ")}")

  def diagnostic(name: String, platform: Platform): String =
    s"$name is unavailable on $platform ($description)"

/** Shared by CLI and programmatic entry points. Exact intent is determined from the complete inventory before regex
  * parsing, exclusions, or platform filtering.
  */
object Selection:
  final case class Result[A](selected: Vector[A], skipped: Vector[A])

  def apply[A](
      inventory: Vector[A],
      includes: Vector[String],
      excludes: Vector[String],
      platform: Platform,
      listing: Boolean = false
  )(name: A => String, eligibility: A => Eligibility): Result[A] =
    val canonical = inventory.map(name).toSet
    val exact = includes.filter(canonical).toSet
    val patterns = includes.filterNot(canonical).map(_.r)
    val excluded = excludes.map(_.r)
    val matches = inventory.filter { entry =>
      val n = name(entry)
      (includes.isEmpty || exact(n) || patterns.exists(_.findFirstIn(n).nonEmpty)) &&
      !excluded.exists(_.findFirstIn(n).nonEmpty)
    }
    require(matches.nonEmpty || listing, "No benchmarks match the selection")
    if listing then Result(matches, Vector.empty)
    else
      val (supported, unsupported) = matches.partition(e => eligibility(e).supports(platform))
      val requested = unsupported.filter(e => exact(name(e)))
      require(requested.isEmpty, requested.map(e => eligibility(e).diagnostic(name(e), platform)).mkString("\n"))
      Result(supported, unsupported)
