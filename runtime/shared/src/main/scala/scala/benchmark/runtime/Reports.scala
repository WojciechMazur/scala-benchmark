package scala.benchmark.runtime

import scala.jdk.CollectionConverters.*

import org.openjdk.jmh.annotations.Mode
import scala.benchmark.internal.reporting.{HtmlReport, ResultTable}

final case class RunResult(
    descriptor: Descriptor,
    mode: Mode,
    configuration: Configuration,
    parameters: Map[String, String],
    forks: Vector[Vector[IterationResult]],
    clock: ClockCalibration,
    metricKind: String = "",
    statisticsOverride: Option[Statistics] = None,
    publicMetric: Option[org.openjdk.jmh.results.Result[?]] = None,
    secondaryOverride: Option[Vector[(String, RunResult)]] = None,
    histogramOverride: Option[Vector[Vector[Vector[(Double, Long)]]]] = None,
    reportParams: Option[org.openjdk.jmh.infra.BenchmarkParams] = None
) {
  lazy val statistics: Statistics = statisticsOverride.getOrElse(if (publicMetric.nonEmpty) {
    Statistics(
      publicMetric.get
        .getStatistics()
        .getRawData()
        .asScala
        .map(e => e.getKey.doubleValue() -> e.getValue.longValue())
        .toVector,
      sampled = mode == Mode.SampleTime && metricKind.isEmpty
    )
  } else if (mode == Mode.SampleTime && metricKind.isEmpty) {
    val histogram = new SampleBuffer
    forks.flatten.flatMap(_.workers).foreach(r => histogram.merge(r.samples))
    Statistics(
      histogram.entries.map { case (v, n) =>
        v / Execution.unitNanos(configuration.unit) -> n
      },
      sampled = true
    )
  } else Statistics(forks.flatten.map(r => r.score -> 1L)))

  /** Member observations retain their original worker/fork/iteration boundaries. */
  lazy val secondaryResults: Vector[(String, RunResult)] = secondaryOverride.getOrElse {
    val base = groupResults ++ auxiliaryResults
    base ++ percentileResults("") ++ base.flatMap { case (name, value) => value.percentileResults(name + ":") }
  }
  private def percentileResults(prefix: String): Vector[(String, RunResult)] = {
    if (mode != Mode.SampleTime || metricKind.nonEmpty) Vector.empty
    else
      Vector(
        "p0.00" -> 0.0,
        "p0.50" -> 50.0,
        "p0.90" -> 90.0,
        "p0.95" -> 95.0,
        "p0.99" -> 99.0,
        "p0.999" -> 99.9,
        "p0.9999" -> 99.99,
        "p1.00" -> 100.0
      ).map { case (name, point) =>
        val observations = forks.map(_.map { iteration =>
          val buffer = new SampleBuffer
          iteration.workers.foreach(worker => buffer.merge(worker.samples))
          val stats = Statistics(
            buffer.entries.map { case (v, n) => v / Execution.unitNanos(configuration.unit) -> n },
            sampled = true
          )
          iteration.copy(score = stats.percentile(point))
        })
        (prefix + name) -> copy(
          descriptor =
            descriptor.copy(name = descriptor.name + ":" + name, members = Vector.empty, counters = Vector.empty),
          forks = observations,
          metricKind = "DERIVATIVE",
          statisticsOverride = Some(Statistics(Vector(statistics.percentile(point) -> 1L)))
        )
      }
  }
  private lazy val groupResults: Vector[(String, RunResult)] =
    if (descriptor.members.size <= 1) Vector.empty
    else
      descriptor.members.zipWithIndex.filter(_._1.threads > 0).map { case (member, subgroup) =>
        val observations = forks.map(_.map { iteration =>
          val workers = iteration.workers.zipWithIndex.collect {
            case (worker, index) if descriptor.worker(index, configuration.threads).getSubgroupIndex() == subgroup =>
              worker
          }
          IterationResult(Execution.aggregate(mode, configuration, workers), workers)
        })
        val threads = configuration.threads / descriptor.members.map(_.threads).sum * member.threads
        member.name -> copy(
          descriptor = descriptor
            .copy(name = descriptor.name + ":" + member.name, members = Vector.empty, counters = Vector.empty),
          configuration = configuration.copy(threads = threads),
          forks = observations
        )
      }
  private lazy val auxiliaryResults: Vector[(String, RunResult)] = {
    if (descriptor.counters.isEmpty) Vector.empty
    else {
      val observed = forks.flatten.flatMap(_.workers.flatMap(_.auxiliary.map(c => c.name -> c.kind))).toSet
      descriptor.counters.map(c => c.name -> c.kind).distinct.filter(observed).collect {
        case (name, kind) if kind == "EVENTS" || mode == Mode.Throughput || mode == Mode.AverageTime =>
          val observations = forks.map(_.flatMap { iteration =>
            val counters = iteration.workers.flatMap { worker =>
              worker.auxiliary.filter(c => c.name == name && c.kind == kind).map(c => worker -> c.value)
            }
            val score =
              if (kind == "EVENTS") counters.map(_._2).sum
              else {
                val values = counters.map { case (worker, value) =>
                  if (mode == Mode.Throughput) value * Execution.unitNanos(configuration.unit) / worker.elapsedNanos
                  else worker.elapsedNanos / value / Execution.unitNanos(configuration.unit)
                }
                if (mode == Mode.Throughput) values.sum else values.sum / values.size
              }
            // Scalar EVENTS provide a zero result; rate/time OPERATIONS do not.
            // JMH omits missing operation observations from iteration aggregation.
            Option.when(counters.nonEmpty)(IterationResult(score, counters.map(_._1)))
          })
          name -> copy(
            descriptor =
              descriptor.copy(name = descriptor.name + ":" + name, members = Vector.empty, counters = Vector.empty),
            forks = observations,
            metricKind = kind,
            statisticsOverride = if (kind == "EVENTS") {
              val scores = observations.flatten.map(_.score -> 1L)
              Some(Statistics(scores ++ Vector.fill(forks.flatten.size - scores.size)(0.0 -> 1L)))
            } else None
          )
      }
    }
  }
  def sampleCount: Long = publicMetric.fold(statistics.count)(_.getSampleCount())
  def percentile(rank: Double): Double =
    publicMetric.fold(statistics.percentile(rank))(_.getStatistics().getPercentile(rank))
  def score: Double = publicMetric.fold(
    if (metricKind == "EVENTS") statistics.values.map { case (v, n) => v * n }.sum else statistics.mean
  )(_.getScore())
  def scoreError: Double =
    publicMetric.fold(if (metricKind == "EVENTS") Double.NaN else statistics.error)(_.getScoreError())
  def scoreConfidence: Vector[Double] = publicMetric.fold(
    if (metricKind == "EVENTS") Vector(score, score) else Vector(score - scoreError, score + scoreError)
  )(_.getScoreConfidence().toVector)
  def unit: String = {
    if (publicMetric.nonEmpty) return publicMetric.get.getScoreUnit()
    if (metricKind == "EVENTS") return "#"
    val suffix = configuration.unit match {
      case "NANOSECONDS"  => "ns"
      case "MICROSECONDS" => "us"
      case "MILLISECONDS" => "ms"
      case "SECONDS"      => "s"
      case "MINUTES"      => "min"
      case "HOURS"        => "hr"
      case "DAYS"         => "day"
    }
    if (mode == Mode.Throughput) "ops/" + suffix else suffix + "/op"
  }
}

final case class ClockCalibration(
    minimumDeltaNanos: Long,
    averageCallNanos: Double
) {
  def available: Boolean = minimumDeltaNanos > 0
}
object ClockCalibration {
  val Unavailable: ClockCalibration = ClockCalibration(0, Double.NaN)
  def measure(): ClockCalibration = {
    var previous = System.nanoTime()
    val start = previous
    var minimum = Long.MaxValue
    var i = 0
    while (i < 10000) {
      val now = System.nanoTime()
      require(now >= previous && now != 0, "Monotonic clock is unavailable")
      val delta = now - previous
      if (delta > 0) minimum = math.min(minimum, delta)
      previous = now
      i += 1
    }
    require(minimum != Long.MaxValue, "Clock has no observable resolution")
    ClockCalibration(minimum, (previous - start) / 10000.0)
  }
}

object Reports {
  val formats: Vector[String] = Vector("text", "csv", "scsv", "json", "latex", "html")

  def render(results: Vector[RunResult], format: String): String = format match {
    case "text"  => text(results)
    case "csv"   => csv(results)
    case "scsv"  => scsv(results)
    case "json"  => json(results)
    case "latex" => latex(results)
    case "html"  => html(results)
    case other   => throw new IllegalArgumentException(s"Unsupported result format: $other")
  }

  def quote(text: String): String = "\"" + text.flatMap {
    case '"'          => "\\\""
    case '\\'         => "\\\\"
    case '\n'         => "\\n"
    case '\r'         => "\\r"
    case '\t'         => "\\t"
    case c if c < ' ' => f"\\u${c.toInt}%04x"
    case c            => c.toString
  } + "\""
  private def number(value: Double): String =
    if (value.isFinite) value.toString
    else if (value == Double.PositiveInfinity) quote("+INF")
    else if (value == Double.NegativeInfinity) quote("-INF")
    else quote("NaN")
  private def array(items: Iterable[String]): String =
    items.mkString("[", ",", "]")
  private def obj(items: Iterable[(String, String)]): String = items
    .map { case (key, value) => quote(key) + ":" + value }
    .mkString("{", ",", "}")
  private def metricJson(run: RunResult): String = {
    val s = run.statistics
    val c = run.configuration
    val raw =
      if (run.metricKind.isEmpty && !scala.benchmark.internal.JsonReportOptions.rawData)
        (if (run.mode == Mode.SampleTime) "rawDataHistogram" else "rawData") -> "[]"
      else if (run.histogramOverride.nonEmpty)
        "rawDataHistogram" -> array(
          run.histogramOverride.get.map(fork =>
            array(
              fork.map(iteration =>
                array(iteration.map { case (value, count) => array(Vector(number(value), count.toString)) })
              )
            )
          )
        )
      else if (run.mode == Mode.SampleTime && run.metricKind.isEmpty) {
        "rawDataHistogram" -> array(
          run.forks.map(fork =>
            array(fork.map { iteration =>
              val merged = new SampleBuffer
              iteration.workers.foreach(r => merged.merge(r.samples))
              array(merged.entries.map { case (v, n) =>
                array(
                  Vector(number(v / Execution.unitNanos(c.unit)), n.toString)
                )
              })
            })
          )
        )
      } else
        "rawData" -> array(
          run.forks.map(f => array(f.map(r => number(r.score))))
        )
    obj(
      Vector(
        "score" -> number(run.score),
        "scoreError" -> number(run.scoreError),
        "scoreConfidence" -> array(
          run.scoreConfidence.map(number)
        ),
        "scoreUnit" -> quote(run.unit),
        "scorePercentiles" -> obj(
          Vector("0.0", "50.0", "90.0", "95.0", "99.0", "99.9", "99.99", "99.999", "99.9999", "100.0")
            .map(p => p -> number(run.percentile(p.toDouble)))
        ),
        raw
      )
    )
  }
  def json(results: Vector[RunResult]): String = {
    scala.benchmark.internal.JsonReportOptions.initialize()
    array(results.map { run =>
      val c = run.configuration
      val nativeMetadata = obj(
        (Vector(
          "os" -> quote(System.getProperty("os.name", "unknown")),
          "arch" -> quote(System.getProperty("os.arch", "unknown")),
          "ignoredSettings" -> array(run.descriptor.warnings.map(quote))
        ) ++ Option
          .when(run.clock.available)(
            Vector(
              "clockMinimumDeltaNanos" -> run.clock.minimumDeltaNanos.toString,
              "clockAverageCallNanos" -> number(run.clock.averageCallNanos)
            )
          )
          .toVector
          .flatten ++ Platform.buildMetadata.map { case (k, v) => k -> quote(v) }).toMap.toVector.sortBy(_._1)
      )
      val supplied = run.reportParams
      obj(
        Vector(
          "jmhVersion" -> quote(supplied.fold(Platform.description + " (JMH API 1.37)")(_.getJmhVersion())),
          "benchmark" -> quote(run.descriptor.name),
          "mode" -> quote(run.mode.shortLabel()),
          "threads" -> c.threads.toString,
          "forks" -> c.forks.toString,
          "jvm" -> quote(supplied.fold("N/A")(_.getJvm())),
          "jvmArgs" -> array(supplied.toVector.flatMap(_.getJvmArgs().asScala).map(quote)),
          "jdkVersion" -> quote(supplied.fold("N/A")(_.getJdkVersion())),
          "vmName" -> quote(supplied.fold(Platform.description)(_.getVmName())),
          "vmVersion" -> quote(supplied.fold(Platform.runtimeVersion)(_.getVmVersion())),
          "warmupIterations" -> c.warmupIterations.toString,
          "warmupTime" -> quote(Configuration.time(c, true).toString),
          "warmupBatchSize" -> c.warmupBatchSize.toString,
          "measurementIterations" -> c.measurementIterations.toString,
          "measurementTime" -> quote(Configuration.time(c, false).toString),
          "measurementBatchSize" -> c.measurementBatchSize.toString,
          "params" -> obj(run.parameters.toVector.sortBy(_._1).map { case (k, v) =>
            k -> quote(v)
          }),
          "primaryMetric" -> metricJson(run),
          "secondaryMetrics" -> obj(run.secondaryResults.map { case (name, member) => name -> metricJson(member) })
        ) ++ Option.when(run.clock.available)(Platform.metadataSection -> nativeMetadata)
      )
    })
  }
  private def expanded(results: Vector[RunResult]): Vector[RunResult] =
    results.flatMap(run => run +: run.secondaryResults.map(_._2))

  private def summary(primary: Vector[RunResult]): (Vector[String], Vector[Vector[String]]) = {
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
  }

  def text(results: Vector[RunResult]): String = {
    val report = ConsoleReport.render(expanded(results))
    if (report.isEmpty) "" else report + "\n"
  }

  def csv(results: Vector[RunResult]): String = delimited(results, ",")
  def scsv(results: Vector[RunResult]): String = delimited(results, ";")

  private def delimited(results: Vector[RunResult], separator: String): String = {
    def cell(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
    val (header, rows) = summary(results)
    (header +: rows).map(_.map(cell).mkString(separator)).mkString("", "\n", "\n")
  }

  /** A standard LaTeX tabular fragment, with no package or PDF-generation dependency. */
  def latex(results: Vector[RunResult]): String = {
    val (header, rows) = summary(results)
    ResultTable.latex(header, rows)
  }

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

  def console(results: Vector[RunResult]): Unit = {
    val text = ConsoleReport.render(expanded(results))
    if (text.nonEmpty) println(text)
  }
  def console(run: RunResult): Unit = console(Vector(run))
}
