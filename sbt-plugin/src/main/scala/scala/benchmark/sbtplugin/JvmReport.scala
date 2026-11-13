package scala.benchmark.sbtplugin

import java.io.File
import scala.benchmark.internal.reporting.{HtmlReport, ResultTable}
import sjsonnew.shaded.scalajson.ast.unsafe.*
import sjsonnew.support.scalajson.unsafe.{CompactPrinter, Parser}

/** Uses the JSON support already supplied by sbt. Benchmark execution stays in JMH. */
private[sbtplugin] object JvmReport {
  def render(file: File, format: String): String = {
    val entries = read(file)
    if (format == "html") HtmlReport.render(entries)
    else {
      val names = entries.flatMap(_.parameters.map(_._1)).distinct.sorted
      val header =
        Vector("Benchmark", "Mode", "Threads", "Samples", "Score", "Score Error (99.9%)", "Unit") ++ names.map(
          "Param: " + _
        )
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
    }
  }

  private def read(file: File): Vector[HtmlReport.Entry] = {
    def fields(value: JValue): Map[String, JValue] = value match {
      case obj: JObject => obj.value.iterator.map(f => f.field -> f.value).toMap
      case _            => Map.empty
    }
    def elements(value: JValue): Vector[JValue] = value match {
      case arr: JArray => arr.value.toVector
      case _           => Vector.empty
    }
    def text(value: JValue): String = value match {
      case s: JString => s.value
      case n: JNumber => n.value
      case _          => CompactPrinter(value)
    }
    def number(value: JValue): Double = text(value).toDouble
    def histogramCount(value: JValue): Long = {
      val array = elements(value)
      if (array.size == 2 && array.forall(_.isInstanceOf[JNumber])) number(array(1)).toLong
      else array.map(histogramCount).sum
    }
    val records = elements(Parser.parseFromFile(file).get)
    def quote(value: String): String = "\"" + value.flatMap {
      case '"'          => "\\\""
      case '\\'         => "\\\\"
      case '\n'         => "\\n"
      case '\r'         => "\\r"
      case '\t'         => "\\t"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c            => c.toString
    } + "\""
    records.flatMap { record =>
      val values = fields(record)
      val metrics = Vector("" -> values("primaryMetric")) ++ fields(
        values.getOrElse("secondaryMetrics", JNull)
      ).toVector.sortBy(_._1)
      metrics.map { case (label, metricValue) =>
        val metric = fields(metricValue)
        val rawCount =
          if (label.matches("(?:.*:)?p[0-9.]+")) 1L
          else
            metric
              .get("rawDataHistogram")
              .map(histogramCount)
              .orElse(metric.get("rawData").map(v => elements(v).map(elements(_).size.toLong).sum))
              .getOrElse(0L)
        val metadata = values.toVector
          .filterNot { case (key, _) => Set("benchmark", "mode", "params", "primaryMetric", "secondaryMetrics")(key) }
          .map { case (key, value) => key -> text(value) } :+ ("Backend" -> "JMH (JVM)")
        val name = text(values("benchmark")) + (if (label.isEmpty) "" else ":" + label)
        val raw = values.toVector
          .sortBy(_._1)
          .map { case (key, value) =>
            val encoded = key match {
              case "benchmark"        => quote(name)
              case "primaryMetric"    => CompactPrinter(metricValue)
              case "secondaryMetrics" => "{}"
              case _                  => CompactPrinter(value)
            }
            quote(key) + ":" + encoded
          }
          .mkString("{", ",", "}")
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
  }
}
