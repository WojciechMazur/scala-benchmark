/*
 * Copyright (c) 2026, Scala Benchmark contributors.
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

import scala.benchmark.internal.reporting.{HtmlReport, ResultTable}

/** Result files in every supported format, and the console summary. */
object Reports:
  val formats: Vector[String] = Vector("text", "csv", "scsv", "json", "latex", "html")

  def render(results: Vector[RunResult], format: String): String = format match
    case "text"  => text(results)
    case "csv"   => csv(results)
    case "scsv"  => scsv(results)
    case "json"  => json(results)
    case "latex" => latex(results)
    case "html"  => html(results)
    case other   => throw new IllegalArgumentException(s"Unsupported result format: $other")

  def json(results: Vector[RunResult]): String = JsonReport.render(results)

  private def expanded(results: Vector[RunResult]): Vector[RunResult] =
    results.flatMap(run => run +: run.secondaryResults.map(_._2))

  private def summary(primary: Vector[RunResult]): (Vector[String], Vector[Vector[String]]) =
    val results = expanded(primary)
    val names = results.flatMap(_.parameters.keys).distinct.sorted
    val header = Vector(
      "Benchmark",
      "Mode",
      "Threads",
      "Samples",
      "Score",
      "Score Error (99.9%)",
      "Unit"
    ) ++ names.map("Param: " + _)
    val rows = results.map { r =>
      Vector(
        r.descriptor.name,
        r.mode.shortLabel(),
        r.configuration.threads.toString,
        r.sampleCount.toString,
        r.score.toString,
        r.scoreError.toString,
        r.unit
      ) ++ names.map(r.parameters.getOrElse(_, ""))
    }
    (header, rows)

  def text(results: Vector[RunResult]): String =
    val report = ConsoleReport.render(expanded(results))
    if report.isEmpty then "" else report + "\n"

  def csv(results: Vector[RunResult]): String = delimited(results, ",")
  def scsv(results: Vector[RunResult]): String = delimited(results, ";")

  private def delimited(results: Vector[RunResult], separator: String): String =
    def cell(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
    val (header, rows) = summary(results)
    (header +: rows).map(_.map(cell).mkString(separator)).mkString("", "\n", "\n")

  /** A standard LaTeX tabular fragment, with no package or PDF-generation dependency. */
  def latex(results: Vector[RunResult]): String =
    val (header, rows) = summary(results)
    ResultTable.latex(header, rows)

  def html(results: Vector[RunResult]): String = HtmlReport.render(expanded(results).map { run =>
    val stats = run.statistics
    val c = run.configuration
    val build = Platform.buildMetadata.toMap
    val metadata = Vector(
      "Backend" -> Platform.description,
      "Runtime version" -> Platform.runtimeVersion,
      "Threads" -> c.threads.toString,
      "Forks" -> c.forks.toString,
      "Warmup iterations" -> c.warmupIterations.toString,
      "Warmup duration (ns)" -> c.warmupNanos.toString,
      "Measurement iterations" -> c.measurementIterations.toString,
      "Measurement duration (ns)" -> c.measurementNanos.toString,
      "Warmup batch size" -> c.warmupBatchSize.toString,
      "Measurement batch size" -> c.measurementBatchSize.toString,
      "Clock minimum delta (ns)" -> run.clock.minimumDeltaNanos.toString,
      "Clock average call (ns)" -> run.clock.averageCallNanos.toString,
      "OS" -> build.getOrElse("os", System.getProperty("os.name", "unknown")),
      "Architecture" -> build.getOrElse("arch", System.getProperty("os.arch", "unknown")),
      "Ignored settings" -> run.descriptor.warnings.mkString("; ")
    ) ++ Platform.buildMetadata.filterNot { case (key, _) => key == "os" || key == "arch" }
    HtmlReport.Entry(
      run.descriptor.name,
      run.mode.shortLabel(),
      run.score,
      run.scoreError,
      run.scoreConfidence,
      run.unit,
      run.sampleCount,
      run.parameters.toVector.sorted,
      Vector("50.0", "90.0", "99.0", "99.9").map(p => p -> run.percentile(p.toDouble)),
      metadata,
      json(Vector(run))
    )
  })

  def console(results: Vector[RunResult]): Unit =
    val text = ConsoleReport.render(expanded(results))
    if text.nonEmpty then println(text)

  def console(run: RunResult): Unit = console(Vector(run))

end Reports
