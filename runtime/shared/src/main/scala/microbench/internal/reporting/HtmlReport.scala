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
package microbench.internal.reporting

import scalatags.Text.all.*
import scalatags.Text.tags2.{
  article,
  details,
  main,
  section,
  style as styleTag,
  summary as summaryTag,
  title as titleTag
}

/** Shared as source with the sbt adapter; it does not depend on runtime or sbt APIs. */
object HtmlReport:

  final case class Entry(
      benchmark: String,
      mode: String,
      score: Double,
      error: Double,
      confidence: Vector[Double],
      unit: String,
      samples: Long,
      parameters: Vector[(String, String)],
      percentiles: Vector[(String, Double)],
      metadata: Vector[(String, String)],
      rawData: String
  )

  private def number(value: Double): String = ReportNumbers.format(value)

  def render(entries: Vector[Entry]): String =
    val parameters = entries.flatMap(_.parameters.map(_._1)).distinct.sorted
    def cell(value: String, numeric: Boolean = false): Frag = td(
      if numeric then cls := "number" else cls := "",
      value
    )
    def numericCell(value: Double): Frag = td(
      cls := "number",
      attr("data-value") := value.toString,
      title := value.toString,
      number(value)
    )
    def resultTable(tableId: String, label: String, headers: Vector[String], rows: Vector[Frag]): Frag =
      div(cls := "table-scroll")(
        table(id := tableId)(
          caption(label),
          thead(tr(headers.map(h => th(attr("scope") := "col", h)))),
          tbody(rows)
        )
      )
    def parameterCells(entry: Entry): Vector[Frag] =
      val values = entry.parameters.toMap
      parameters.map(p => cell(values.getOrElse(p, "—")))
    val summaryTable = resultTable(
      "summary",
      "Benchmark results",
      Vector("Benchmark", "Mode", "Samples", "Score", "± error (99.9%)", "Confidence interval", "Unit") ++
        parameters.map("Param: " + _),
      entries.map { entry =>
        val interval =
          if entry.confidence.size == 2 && entry.confidence.forall(_.isFinite) then
            entry.confidence.map(number).mkString("[", ", ", "]")
          else "—"
        tr(
          cell(entry.benchmark),
          cell(entry.mode),
          cell(entry.samples.toString, true),
          numericCell(entry.score),
          numericCell(entry.error),
          td(cls := "number", attr("data-value") := entry.confidence.mkString("[", ", ", "]"), interval),
          cell(entry.unit),
          parameterCells(entry)
        )
      }
    )
    val sampled = entries.filter(_.percentiles.nonEmpty)
    val points = Vector("50.0", "90.0", "99.0", "99.9")
    val percentileTables =
      if sampled.isEmpty then Vector.empty[Frag]
      else
        Vector(
          resultTable(
            "percentiles",
            "Percentiles",
            Vector("Benchmark", "Mode", "p50", "p90", "p99", "p99.9", "Unit") ++ parameters.map("Param: " + _),
            sampled.map { entry =>
              val values = entry.percentiles.toMap
              tr(
                cell(entry.benchmark),
                cell(entry.mode),
                points.map(p => values.get(p).fold(cell("—", true))(numericCell)),
                cell(entry.unit),
                parameterCells(entry)
              )
            }
          )
        )
    val detailSections = entries.zipWithIndex.map { case (entry, index) =>
      article(
        h3(entry.benchmark, " · ", entry.mode),
        dl(entry.metadata.sortBy(_._1).map { case (key, value) => frag(dt(key), dd(value)) }),
        details(summaryTag("Raw measurements"), pre(id := s"raw-$index", entry.rawData))
      )
    }
    val count = entries.size
    val configurations = if count == 1 then "benchmark configuration" else "benchmark configurations"
    "<!DOCTYPE html>\n" + html(lang := "en")(
      head(
        meta(charset := "UTF-8"),
        meta(name := "viewport", content := "width=device-width, initial-scale=1"),
        titleTag("SMH report"),
        styleTag(stylesheet)
      ),
      body(
        main(
          header(h1("SMH report"), p(cls := "subtitle", s"$count $configurations · self-contained report")),
          summaryTable,
          percentileTables,
          p(
            cls := "note",
            "Error values are 99.9% confidence half-widths. — means unavailable. Compare results with the same mode, unit, parameters, and execution settings."
          ),
          section(aria.label := "Execution details", h2("Execution details"), detailSections)
        )
      )
    ).render + "\n"

  end render

  private val stylesheet =
    """
:root{color-scheme:light dark;--bg:#f4f6fa;--panel:#fff;--ink:#182338;--muted:#536174;--line:#dce2ec;--accent:#2459c4}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.6 system-ui,sans-serif}
main{max-width:1500px;margin:auto;padding:2rem}h1{margin:0;font-size:2rem;letter-spacing:-.04em}
.subtitle,.note{color:var(--muted)}.table-scroll{overflow:auto;margin:1.5rem 0;border:1px solid var(--line);border-radius:12px;background:var(--panel)}
table{width:100%;border-collapse:collapse}caption{text-align:left;font-size:1.1rem;font-weight:650;padding:1rem}
th,td{padding:.7rem 1rem;text-align:left;border-top:1px solid var(--line);vertical-align:top;white-space:pre-wrap}
th{font-size:.8rem;color:var(--muted);white-space:nowrap}tbody tr:hover{background:color-mix(in srgb,var(--accent) 5%,transparent)}
.number{text-align:right;font-variant-numeric:tabular-nums;white-space:nowrap}article{margin:1rem 0;padding:1rem 1.25rem;border:1px solid var(--line);border-radius:12px;background:var(--panel)}
h3{overflow-wrap:anywhere}dl{display:grid;grid-template-columns:minmax(10rem,1fr) 3fr;gap:.3rem 1rem}dt{color:var(--muted)}dd{margin:0;overflow-wrap:anywhere;white-space:pre-wrap}
summary{cursor:pointer;color:var(--accent)}pre{overflow:auto;font:12px/1.6 ui-monospace,monospace;white-space:pre-wrap;overflow-wrap:anywhere}
@media(prefers-color-scheme:dark){:root{--bg:#101723;--panel:#182131;--ink:#edf2fa;--muted:#afbdd0;--line:#344156;--accent:#96b8ff}}
@media(max-width:640px){main{padding:1rem}dl{display:block}dd{margin-bottom:.7rem}}
"""

end HtmlReport

object ReportNumbers:

  def format(value: Double): String =
    if value.isNaN then "—"
    else if value == Double.PositiveInfinity then "∞"
    else if value == Double.NegativeInfinity then "−∞"
    else if value == 0.0 then "0"
    else
      val text = String.format("%.6g", Double.box(value)).replace(',', '.')
      val split = text.indexOf('e')
      val mantissa = if split < 0 then text else text.take(split)
      val compact =
        if mantissa.contains('.') then mantissa.reverse.dropWhile(_ == '0').stripPrefix(".").reverse else mantissa
      if split < 0 then compact else compact + "e" + text.drop(split + 1).toInt.toString
