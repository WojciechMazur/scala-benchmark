import scala.sys.process.*

import pl.project13.scala.sbt.JmhPlugin.JmhKeys.Jmh
import sbt.{*, given}
import sbt.Keys.*

object AggregationChecks:
  val verifyAggregation = taskKey[Unit]("Compare public result edge cases against actual upstream JMH")

  def settings(backend: String): Seq[Setting[?]] = Seq(
    verifyAggregation := Def.uncached {
      val repository = FixtureBuild.root
      val evidence = repository / "target/execution-parity/aggregation" / backend
      IO.createDirectory(evidence)
      val directory = baseDirectory.value
      // Benchmark output goes to the process's own stdout on every platform, as in JMH.
      val forks = if backend == "jvm" then Seq("-f", "0") else Nil
      val command = FixtureBuild.benchmarkCommand(backend).value ++ Seq("AggregationEdgeBenchmark", "-foe", "true")
      val log = Process(command ++ forks, directory).!!
      val observations = log.linesIterator
        .filter(_.contains("AGGREGATION\t"))
        .map(line => line.substring(line.indexOf("AGGREGATION\t")))
        .map { line =>
          val columns = line.split("\t")
          columns(1) -> columns(2).split(",").toVector
        }
        .toVector
      IO.write(evidence / "execution.log", log)
      assert(
        observations.size > 100 && observations.map(_._1).distinct.size == observations.size,
        s"Invalid observation inventory: ${observations.size} rows; ${observations.map(_._1).distinct.size} distinct"
      )
      val counters = ujson.read(IO.read(directory / "numeric-counters.json")).arr.head("secondaryMetrics").obj
      assert(counters.size == 13 && !counters.contains("zeroOperations"), "Unexpected single-shot counter surface")
      counters.foreach { case (name, value) =>
        val expected = if name == "methodValue" then Vector(208.0, 214.0) else Vector(106.0, 111.0)
        assert(value("rawData").arr.head.arr.map(_.num).toVector == expected, s"$name: wrong reset/teardown timing")
        assert(value("score").num == expected.sum)
      }
      IO.copyFile(directory / "numeric-counters.json", evidence / "numeric-counters.json")
      val api = ujson.read(IO.read(directory / "counter-api.json")).arr
      assert(api.size == 1 && api.head("primaryMetric")("score").num.isFinite, "Public counter API probe failed")
      IO.copyFile(directory / "counter-api.json", evidence / "counter-api.json")
      for format <- Vector("text", "csv", "scsv", "html", "latex") do
        val report = IO.read(directory / s"counters.$format")
        assert(
          report.contains("byteValue") && report.contains("methodValue") && report.contains("217") && report.contains(
            "422"
          ),
          "Counter observations lost from " + format
        )
        assert(!report.contains("zeroOperations"), "Omitted counter leaked into " + format)
        IO.copyFile(directory / s"counters.$format", evidence / s"counters.$format")
      val zero = ujson.read(IO.read(directory / "zero-operations.json")).arr.head
      assert(
        zero("secondaryMetrics")("zeroOperations")("score").str == "+INF",
        "Infinity did not retain JMH JSON encoding"
      )
      IO.copyFile(directory / "zero-operations.json", evidence / "zero-operations.json")
      val actual = ujson.Obj.from(observations.map { case (key, values) => key -> ujson.Arr.from(values) })
      IO.write(evidence / "observations.json", ujson.write(actual, indent = 2))
      IO.write(evidence / "execution.log", log)
      val converter = fileConverter.value
      val fixtureSources = (Compile / sources).value
      val dependencies = (Compile / dependencyClasspath).value
        .map(e => converter.toPath(e.data).toFile)
        .filter(path =>
          path.isFile &&
            (path.getName.startsWith("scala-benchmark-") || path.getName.startsWith("jmh-") ||
              path.getName
                .startsWith("commons-math3"))
        )
      val plugins = (Compile / scalacOptions).value
        .filter(_.startsWith("-Xplugin:"))
        .flatMap(_.stripPrefix("-Xplugin:").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
        .map(file)
        .filter(_.isFile)
      val support = file(sys.props("benchmark.scripted.support"))
      IO.write(
        evidence / "provenance.json",
        ujson.write(
          ujson.Obj(
            "backend" -> backend,
            "scala" -> scalaVersion.value,
            "jmh" -> "1.37",
            "jdk" -> sys.props("java.runtime.version"),
            "os" -> sys.props("os.name"),
            "arch" -> sys.props("os.arch"),
            "artifacts" -> ujson.Arr.from(
              (fixtureSources ++ dependencies ++ plugins ++ Seq(
                support / "AggregationChecks.scala",
                (LocalRootProject / baseDirectory).value / "build.sbt"
              )).map(path =>
                ujson.Obj(
                  "file" -> path.getName,
                  "sha256" ->
                    FixtureBuild.sha256(path)
                )
              )
            )
          ),
          indent = 2
        )
      )
      if backend != "jvm" then
        val expected = ujson.read(IO.read(evidence.getParentFile / "jvm/observations.json"))
        assert(actual.obj.keySet == expected.obj.keySet, "Result observation inventory differs from JMH")
        val differences = actual.obj.toVector.flatMap { case (key, values) =>
          assert(expected(key).arr.size == values.arr.size, "Result shape differs: " + key)
          expected(key).arr.zip(values.arr).zipWithIndex.collect {
            case ((a, b), index) if {
                  val x = a.str.toDouble; val y = b.str.toDouble
                  val intervalBound =
                    if !key.startsWith("statistics/") && Set(2, 8, 9)(index) then
                      1e-9 * math.sqrt(expected(key)(7).str.toDouble / expected(key)(1).str.toDouble)
                    else 0.0
                  if key.startsWith("exact/") then a.str != b.str
                  else
                    !((x.isFinite && y.isFinite &&
                      math
                        .abs(x - y) <= intervalBound && intervalBound > 0) ||
                      (x == y &&
                        (x != 0 ||
                          java.lang.Double
                            .doubleToRawLongBits(x) ==
                          java.lang.Double
                            .doubleToRawLongBits(y))) ||
                      (x.isNaN && y.isNaN) ||
                      (x.isFinite && y.isFinite && x != 0 &&
                        math
                          .abs((x - y) / x) <= 1e-12))
                } =>
              s"$key[$index]: expected=${a.str} actual=${b.str}"
          }
        }
        IO.write(evidence / "differences.txt", differences.mkString("\n"))
        assert(differences.isEmpty, differences.take(30).mkString("\n"))
      end if
    }
  )

end AggregationChecks
