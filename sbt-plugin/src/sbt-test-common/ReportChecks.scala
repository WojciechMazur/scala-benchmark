import sbt.{*, given}
import sbt.Keys.*
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.{XPathConstants, XPathFactory}
import org.xml.sax.InputSource
import org.w3c.dom.{Document, NodeList}

object ReportChecks {
  val verifyAllFormats = taskKey[Unit]("Validate report contents and escaping in every export format")
  val verifyAllModes = taskKey[Unit]("Verify HTML and JSON measurements for every benchmark mode")
  private val payload = "a;b,\"quoted\"\n<script>alert('x')</script>&_%#$price{}^~\\東京"
  private val benchmarkName = "scala.benchmark.examples.ReportFormatsBenchmark.execute"

  def settings(backend: String): Seq[Setting[?]] = Seq(
    verifyAllFormats := Def.uncached {
      val directory = baseDirectory.value
      val report = ujson.read(IO.read(directory / "report.json")).arr
      assert(report.size == 1)
      checkJson(report.head, backend)

      for ((name, separator) <- Seq("csv" -> ',', "scsv" -> ';')) {
        val records = delimited(IO.read(directory / s"report.$name"), separator)
        assert(records.size == 2 && records.map(_.size).distinct.size == 1, s"Malformed $name")
        val values = records.head.zip(records(1)).toMap
        assert(values("Benchmark") == benchmarkName)
        assert(values("Mode") == "avgt")
        assert(values("Threads").toInt == 1 && values("Samples").toInt == 3)
        assert(values("Score").toDouble.isFinite && values("Score").toDouble >= 0)
        assert(values("Score Error (99.9%)").toDouble.isFinite)
        assert(values("Unit") == "ns/op")
        assert(values("Param: payload") == payload, s"$name did not preserve parameter content")
      }

      val text = IO.read(directory / "report.txt")
      assert(text.contains("ReportFormatsBenchmark.execute") && text.contains("avgt") && text.contains("ns/op"))
      assert(text.contains("東京") && !text.contains("NaN"))
      assert(text.contains(payload) || text.contains(payload.replace("\n", "\\n")))

      val latex = IO.read(directory / "report.tex")
      assert(latex.contains("\\begin{tabular}") && latex.contains("\\end{tabular}"))
      assert(latex.contains("ReportFormatsBenchmark.execute") && latex.contains("avgt"))
      assert(latex.contains("ns/op") && latex.contains("payload") && latex.contains("東京"))
      {
        assert(latex.contains("\\%") && latex.contains("\\&") && latex.contains("\\_"))
        assert(latex.contains("\\#") && latex.contains("\\$") && latex.contains("\\textbackslash{}"))
        val rows = latex.linesIterator.filter(_.endsWith(" \\\\")).toVector
        assert(rows.size == 2)
        assert(rows.forall(splitLatexColumns(_).size == 8), "LaTeX columns corrupted by parameter text")
      }

      val html = IO.read(directory / "report.html")
      assert(html.startsWith("<!DOCTYPE html>"))
      val document = parseHtml(html)
      assert(
        nodes(document, "//script | //link | //*[@src] | //a[@href]").getLength == 0,
        "Report must not execute parameter content or load external resources"
      )
      assert(nodes(document, "//@*[starts-with(name(), 'on')]").getLength == 0)
      assert(nodes(document, "//style").getLength == 1)
      assert(nodes(document, "//meta[@name='viewport']").getLength == 1)
      val cells = nodes(document, "//table[@id='summary']/tbody/tr/td")
      assert(cells.getLength == 8)
      assert(cells.item(0).getTextContent == benchmarkName)
      assert(cells.item(1).getTextContent == "avgt")
      assert(cells.item(2).getTextContent.toLong == 3)
      assert(cells.item(6).getTextContent == "ns/op")
      assert(cells.item(7).getTextContent == jsonPayload(backend), "HTML changed parameter content")

      val raw = ujson.read(nodes(document, "//pre[@id='raw-0']").item(0).getTextContent)
      val result = if (raw.isInstanceOf[ujson.Arr]) raw.arr.head else raw
      checkJson(result, backend)
      val metric = result("primaryMetric")
      assert(cells.item(3).getAttributes.getNamedItem("data-value").getNodeValue.toDouble == metric("score").num)
      assert(cells.item(4).getAttributes.getNamedItem("data-value").getNodeValue.toDouble == metric("scoreError").num)
      val confidence = cells
        .item(5)
        .getAttributes
        .getNamedItem("data-value")
        .getNodeValue
        .stripPrefix("[")
        .stripSuffix("]")
        .split(",")
        .map(_.trim.toDouble)
      assert(confidence.toSeq == metric("scoreConfidence").arr.map(_.num).toSeq)
      val percentiles = nodes(document, "//table[@id='percentiles']/tbody/tr/td")
      assert(percentiles.getLength == 8 && percentiles.item(7).getTextContent == jsonPayload(backend))
      for ((point, index) <- Seq("50.0", "90.0", "99.0", "99.9").zipWithIndex)
        assert(
          percentiles.item(index + 2).getAttributes.getNamedItem("data-value").getNodeValue.toDouble == metric(
            "scorePercentiles"
          )(point).num
        )
      val keys = nodes(document, "//article/dl/dt")
      val data = nodes(document, "//article/dl/dd")
      assert(keys.getLength == data.getLength)
      val metadata = (0 until keys.getLength).map { i =>
        keys.item(i).getTextContent.toLowerCase.filter(_.isLetterOrDigit) -> data.item(i).getTextContent
      }.toMap
      assert(metadata("threads") == "1" && metadata("forks") == "1" && metadata("measurementiterations") == "3")
      assert(metadata("backend").nonEmpty)
    },
    verifyAllModes := Def.uncached {
      val directory = baseDirectory.value
      val modes = Set("thrpt", "avgt", "sample", "ss")
      val json = ujson.read(IO.read(directory / "modes.json")).arr
      assert(json.map(_("mode").str).toSet == modes)
      val document = parseHtml(IO.read(directory / "modes.html"))
      val rows = nodes(document, "//table[@id='summary']/tbody/tr")
      assert(rows.getLength == json.map(result => 1 + result("secondaryMetrics").obj.size).sum)
      val seen = (0 until rows.getLength).map { index =>
        val raw = ujson.read(nodes(document, s"//pre[@id='raw-$index']").item(0).getTextContent)
        val result = if (raw.isInstanceOf[ujson.Arr]) raw.arr.head else raw
        val metric = result("primaryMetric")
        val cells = rows.item(index).getChildNodes
        assert(cells.getLength == 8)
        assert(cells.item(1).getTextContent == result("mode").str)
        assert(cells.item(3).getAttributes.getNamedItem("data-value").getNodeValue.toDouble == metric("score").num)
        assert(cells.item(6).getTextContent == metric("scoreUnit").str)
        assert(cells.item(7).getTextContent == jsonPayload(backend))
        def histogramCount(value: ujson.Value): Long = {
          val array = value.arr
          if (array.size == 2 && array.forall(_.isInstanceOf[ujson.Num])) array(1).num.toLong
          else array.map(histogramCount).sum
        }
        val expectedSamples = metric.obj
          .get("rawDataHistogram")
          .map(histogramCount)
          .getOrElse(metric("rawData").arr.map(_.arr.size.toLong).sum)
        val count = if (result("benchmark").str.matches(".*:p[0-9.]+")) 1L else expectedSamples
        assert(count > 0 && cells.item(2).getTextContent.toLong == count)
        result("mode").str
      }.toSet
      assert(seen == modes)
    }
  )

  private def checkJson(result: ujson.Value, backend: String): Unit = {
    assert(result("benchmark").str == benchmarkName && result("mode").str == "avgt")
    assert(result("threads").num == 1 && result("forks").num == 1)
    assert(result("warmupIterations").num == 0 && result("measurementIterations").num == 3)
    assert(
      result("params")("payload").str == jsonPayload(backend),
      "Parameter mismatch: expected " + ujson.write(payload) + " got " + ujson.write(result("params")("payload"))
    )
    val metric = result("primaryMetric")
    assert(metric("score").num.isFinite && metric("score").num >= 0)
    assert(metric("scoreError").num.isFinite && metric("scoreUnit").str == "ns/op")
    assert(metric("scoreConfidence").arr.size == 2)
    val iterations = metric("rawData").arr
    assert(iterations.size == 1 && iterations.head.arr.size == 3)
    assert(iterations.head.arr.forall(v => v.num.isFinite && v.num >= 0))
    for (point <- Seq("50.0", "90.0", "99.0", "99.9"))
      assert(metric("scorePercentiles")(point).num.isFinite)
  }

  // JMH 1.37's JSON exporter removes line breaks in string fields. HTML uses that JSON unchanged.
  private def jsonPayload(backend: String): String = if (backend == "jvm") payload.replace("\n", "") else payload

  private def parseHtml(html: String): Document = {
    assert(html.startsWith("<!DOCTYPE html>"))
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "")
    factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    factory.newDocumentBuilder().parse(new InputSource(new StringReader(html)))
  }

  private def nodes(document: Document, expression: String): NodeList =
    XPathFactory.newInstance().newXPath().evaluate(expression, document, XPathConstants.NODESET).asInstanceOf[NodeList]

  /** Parse quoted multiline fields, rather than checking output with substring matching. */
  private def delimited(text: String, separator: Char): Vector[Vector[String]] = {
    val rows = Vector.newBuilder[Vector[String]]
    var row = Vector.empty[String]
    val field = new StringBuilder
    var quoted = false
    var i = 0
    def endField(): Unit = { row :+= field.toString; field.clear() }
    def endRow(): Unit = { endField(); rows += row; row = Vector.empty }
    while (i < text.length) {
      val c = text(i)
      if (quoted) {
        if (c == '"' && i + 1 < text.length && text(i + 1) == '"') { field.append('"'); i += 1 }
        else if (c == '"') quoted = false
        else field.append(c)
      } else if (c == '"' && field.isEmpty) quoted = true
      else if (c == separator) endField()
      else if (c == '\n') endRow()
      else if (c != '\r') field.append(c)
      i += 1
    }
    assert(!quoted, "Unclosed quoted field")
    if (field.nonEmpty || row.nonEmpty) endRow()
    rows.result()
  }

  private def splitLatexColumns(row: String): Vector[String] = {
    val result = Vector.newBuilder[String]
    var start = 0
    var slashes = 0
    row.indices.foreach { i =>
      if (row(i) == '&' && slashes % 2 == 0) { result += row.substring(start, i); start = i + 1 }
      slashes = if (row(i) == '\\') slashes + 1 else 0
    }
    result += row.substring(start)
    result.result()
  }
}
