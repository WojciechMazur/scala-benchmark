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

import java.io.File

import scala.benchmark.internal.reporting.{HtmlReport, ResultTable}

import sbt.*
import sjsonnew.shaded.scalajson.ast.unsafe.*
import sjsonnew.support.scalajson.unsafe.{CompactPrinter, Parser}

/** HTML and LaTeX reports for the JVM, rendered from JMH's own JSON results. Benchmark execution stays in JMH. */
private[sbtplugin] object JvmReport:
  private val extensions = Map("html" -> "html", "latex" -> "tex")

  /** A `Jmh / run` invocation asking for a result format that JMH does not provide. */
  final case class Request(format: String, output: File, private val forwarded: Seq[String]):

    /** The original arguments, with the result format redirected to a JSON file. */
    def jmhArguments(json: File): Seq[String] = forwarded ++ Seq("-rf", "json", "-rff", json.getAbsolutePath)

  object Request:

    def apply(arguments: Seq[String], workingDirectory: File): Option[Request] =
      def option(name: String): Option[String] =
        val index = arguments.indexOf(name)
        require(index < 0 || index + 1 < arguments.size, s"Missing value of $name")
        Option.when(index >= 0)(arguments(index + 1))
      for
        format <- option("-rf").map(_.toLowerCase)
        extension <- extensions.get(format)
      yield
        val selected = file(option("-rff").getOrElse("jmh-result." + extension))
        val output = if selected.isAbsolute then selected else workingDirectory / selected.getPath
        Request(format, output, withoutResultOptions(arguments))

    private def withoutResultOptions(arguments: Seq[String]): Seq[String] = arguments match
      case ("-rf" | "-rff") +: _ +: rest => withoutResultOptions(rest)
      case argument +: rest              => argument +: withoutResultOptions(rest)
      case _                             => Nil

  def render(file: File, format: String): String =
    val entries = read(file)
    if format == "html" then HtmlReport.render(entries)
    else
      val names = entries.flatMap(_.parameters.map(_._1)).distinct.sorted
      val header = Vector("Benchmark", "Mode", "Threads", "Samples", "Score", "Score Error (99.9%)", "Unit") ++
        names.map("Param: " + _)
      val rows = entries.map { entry =>
        val metadata = entry.metadata.toMap
        val parameters = entry.parameters.toMap
        Vector(
          entry.benchmark,
          entry.mode,
          metadata.getOrElse("threads", "1"),
          entry.samples.toString,
          entry.score.toString,
          entry.error.toString,
          entry.unit
        ) ++ names.map(parameters.getOrElse(_, ""))
      }
      ResultTable.latex(header, rows)

  private def read(file: File): Vector[HtmlReport.Entry] =
    def fields(value: JValue): Map[String, JValue] = value match
      case obj: JObject => obj.value.iterator.map(f => f.field -> f.value).toMap
      case _            => Map.empty
    def elements(value: JValue): Vector[JValue] = value match
      case arr: JArray => arr.value.toVector
      case _           => Vector.empty
    def text(value: JValue): String = value match
      case s: JString => s.value
      case n: JNumber => n.value
      case _          => CompactPrinter(value)
    def number(value: JValue): Double = text(value).toDouble
    def histogramCount(value: JValue): Long =
      val array = elements(value)
      if array.size == 2 && array.forall(_.isInstanceOf[JNumber]) then number(array(1)).toLong
      else array.map(histogramCount).sum
    val records = elements(Parser.parseFromFile(file).get)
    records.flatMap { record =>
      val values = fields(record)
      val metrics = Vector("" -> values("primaryMetric")) ++ fields(
        values.getOrElse("secondaryMetrics", JNull)
      ).toVector.sortBy(_._1)
      metrics.map { case (label, metricValue) =>
        val metric = fields(metricValue)
        val rawCount =
          if label.matches("(?:.*:)?p[0-9.]+") then 1L
          else
            metric
              .get("rawDataHistogram")
              .map(histogramCount)
              .orElse(metric.get("rawData").map(v => elements(v).map(elements(_).size.toLong).sum))
              .getOrElse(0L)
        val metadata = values.toVector
          .filterNot { case (key, _) => Set("benchmark", "mode", "params", "primaryMetric", "secondaryMetrics")(key) }
          .map { case (key, value) => key -> text(value) } :+ ("Backend" -> "JMH (JVM)")
        val name = text(values("benchmark")) + (if label.isEmpty then "" else ":" + label)
        // One record per metric: the metric becomes the record's primary one.
        val raw = CompactPrinter(JObject(values.toArray.sortBy(_._1).map {
          case ("benchmark", _)        => JField("benchmark", JString(name))
          case ("primaryMetric", _)    => JField("primaryMetric", metricValue)
          case ("secondaryMetrics", _) => JField("secondaryMetrics", JObject(Array.empty[JField]))
          case (key, value)            => JField(key, value)
        }))
        HtmlReport.Entry(
          name,
          text(values("mode")),
          number(metric("score")),
          number(metric("scoreError")),
          elements(metric("scoreConfidence")).map(number),
          text(metric("scoreUnit")),
          rawCount,
          fields(values.getOrElse("params", JNull)).toVector
            .map { case (key, value) => key -> text(value) }
            .sortBy(_._1),
          fields(metric.getOrElse("scorePercentiles", JNull)).toVector.map { case (key, value) =>
            key -> number(value)
          },
          metadata,
          raw
        )
      }
    }

  end read

end JvmReport
