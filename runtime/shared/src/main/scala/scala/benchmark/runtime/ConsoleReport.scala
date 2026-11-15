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
package scala.benchmark.runtime

import org.openjdk.jmh.annotations.Mode

/** Console presentation only: exported measurements retain their configured units and precision. */
private[runtime] object ConsoleReport:
  private case class Column(name: String, numeric: Boolean = false)

  def render(results: Vector[RunResult]): String =
    if results.isEmpty then return ""
    val packages = results.map(_.descriptor.name.split('.').dropRight(2).toVector)
    val common = packages.tail.foldLeft(packages.head) { (prefix, next) =>
      prefix.zip(next).takeWhile { case (a, b) => a == b }.map(_._1)
    }
    val prefix = if common.isEmpty then "" else common.mkString("", ".", ".")
    val parameters = results.flatMap(_.parameters.keys).distinct.sorted
    def identity(run: RunResult): Vector[String] =
      Vector(run.descriptor.name.stripPrefix(prefix), run.mode.shortLabel())
    def params(run: RunResult): Vector[String] = parameters.map(run.parameters.getOrElse(_, "—"))
    val identityColumns = Vector(Column("Benchmark"), Column("Mode"))
    val parameterColumns = parameters.map(name => Column(s"Param: $name"))
    val summary = table(
      identityColumns ++ Vector(
        Column("Samples", true),
        Column("Score", true),
        Column("± error (99.9%)", true),
        Column("Unit")
      ) ++ parameterColumns,
      results.map { run =>
        val stats = run.statistics
        identity(run) ++ Vector(stats.count.toString, number(run.score), number(run.scoreError), run.unit) ++ params(
          run
        )
      }
    )
    val sampled = results.filter(run => run.mode == Mode.SampleTime || run.mode == Mode.SingleShotTime)
    val percentiles =
      if sampled.isEmpty then Vector.empty
      else
        val points = Vector(50.0, 90.0, 99.0, 99.9)
        Vector("", "Percentiles") ++ table(
          identityColumns ++ Vector("p50", "p90", "p99", "p99.9").map(Column(_, true)) ++
            Vector(Column("Unit")) ++ parameterColumns,
          sampled.map(run =>
            identity(run) ++ points.map(p => number(run.statistics.percentile(p))) ++
              Vector(run.unit) ++ params(run)
          )
        )
    val context = if prefix.isEmpty then Vector.empty else Vector("Package: " + prefix.stripSuffix("."))
    val notes =
      if results.exists(_.scoreError.isNaN) then
        Vector("", "—: confidence interval unavailable; at least 3 samples are required.")
      else Vector.empty
    (context ++ summary ++ percentiles ++ notes).mkString("\n")

  end render

  private def number(value: Double): String =
    scala.benchmark.internal.reporting.ReportNumbers.format(value)

  private def cell(value: String): String = value.flatMap {
    case '\n'                      => "\\n"
    case '\r'                      => "\\r"
    case '\t'                      => "\\t"
    case c if c < ' ' || c == 0x7f => f"\\u${c.toInt}%04x"
    case c                         => c.toString
  }

  private def table(columns: Vector[Column], values: Vector[Vector[String]]): Vector[String] =
    val rows = (columns.map(_.name) +: values).map(_.map(cell))
    val widths = columns.indices.map(i => rows.map(row => width(row(i))).max).toVector
    def row(cells: Vector[String]): String = cells.zipWithIndex
      .map { case (text, i) =>
        val padding = " " * (widths(i) - width(text))
        if columns(i).numeric then padding + text else text + padding
      }
      .mkString("│ ", " │ ", " │")
    def rule(left: String, middle: String, right: String): String =
      widths.map(n => "─" * (n + 2)).mkString(left, middle, right)
    Vector(rule("┌", "┬", "┐"), row(rows.head), rule("├", "┼", "┤")) ++
      rows.tail.map(row) ++ Vector(rule("└", "┴", "┘"))

  // Count display columns rather than UTF-16 units for combining marks and common wide glyphs.
  private def width(text: String): Int =
    var index = 0
    var columns = 0
    while index < text.length do
      val point = text.codePointAt(index)
      val category = Character.getType(point)
      val combining = category == Character.NON_SPACING_MARK || category == Character.ENCLOSING_MARK ||
        category == Character.COMBINING_SPACING_MARK || category == Character.FORMAT
      val wide = point >= 0x1100 &&
        (point <= 0x115f || point == 0x2329 || point == 0x232a ||
          (point >= 0x2e80 && point <= 0xa4cf && point != 0x303f) ||
          (point >= 0xac00 && point <= 0xd7a3) ||
          (point >= 0xf900 && point <= 0xfaff) ||
          (point >= 0xfe10 && point <= 0xfe6f) ||
          (point >= 0xff01 && point <= 0xff60) ||
          (point >= 0xffe0 && point <= 0xffe6) ||
          (point >= 0x1f300 && point <= 0x1faff) ||
          (point >= 0x20000 && point <= 0x3fffd))
      if !combining then columns += (if wide then 2 else 1)
      index += Character.charCount(point)
    columns

end ConsoleReport
