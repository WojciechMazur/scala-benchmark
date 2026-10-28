package scala.scalanative.jmh

import org.openjdk.jmh.annotations.Mode

final case class RunResult(
    descriptor: Descriptor,
    mode: Mode,
    configuration: Configuration,
    parameters: Map[String, String],
    forks: Vector[Vector[IterationResult]],
    clock: ClockCalibration
) {
  lazy val statistics: Statistics = if (mode == Mode.SampleTime) {
    val histogram = new SampleBuffer
    forks.flatten.flatMap(_.workers).foreach(r => histogram.merge(r.samples))
    Statistics(histogram.entries.map { case (v, n) =>
      v / Execution.unitNanos(configuration.unit) -> n
    })
  } else Statistics(forks.flatten.map(r => r.score -> 1L))
  def unit: String = {
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
)
object ClockCalibration {
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
  private lazy val buildMetadata: Vector[(String, String)] = {
    val properties = new java.util.Properties
    val stream = getClass.getResourceAsStream("/native-jmh/build.properties")
    if (stream != null) try properties.load(stream)
    finally stream.close()
    import scala.jdk.CollectionConverters._
    properties
      .stringPropertyNames()
      .asScala
      .toVector
      .sorted
      .map(k => k -> quote(properties.getProperty(k)))
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
    if (value.isFinite) value.toString else quote(value.toString)
  private def array(items: Iterable[String]): String =
    items.mkString("[", ",", "]")
  private def obj(items: Iterable[(String, String)]): String = items
    .map { case (key, value) => quote(key) + ":" + value }
    .mkString("{", ",", "}")
  def json(results: Vector[RunResult]): String = array(results.map { run =>
    val s = run.statistics
    val c = run.configuration
    val raw =
      if (run.mode == Mode.SampleTime) {
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
    val metric = Vector(
      "score" -> number(s.mean),
      "scoreError" -> number(s.error),
      "scoreConfidence" -> array(
        Vector(number(s.mean - s.error), number(s.mean + s.error))
      ),
      "scoreUnit" -> quote(run.unit),
      "scorePercentiles" -> obj(
        Vector(0.0, 50.0, 90.0, 95.0, 99.0, 99.9, 99.99, 100.0).map(p =>
          p.toString -> number(s.percentile(p))
        )
      ),
      raw
    )
    val nativeMetadata = obj(
      Vector(
        "os" -> quote(System.getProperty("os.name", "unknown")),
        "arch" -> quote(System.getProperty("os.arch", "unknown")),
        "clockMinimumDeltaNanos" -> run.clock.minimumDeltaNanos.toString,
        "clockAverageCallNanos" -> number(run.clock.averageCallNanos),
        "ignoredSettings" -> array(run.descriptor.warnings.map(quote))
      ) ++ buildMetadata
    )
    obj(
      Vector(
        "jmhVersion" -> quote("Native JMH (API 1.37)"),
        "benchmark" -> quote(run.descriptor.name),
        "mode" -> quote(run.mode.shortLabel()),
        "threads" -> c.threads.toString,
        "forks" -> c.forks.toString,
        "jvm" -> quote("N/A"),
        "jvmArgs" -> "[]",
        "jdkVersion" -> quote("N/A"),
        "vmName" -> quote(Platform.description),
        "vmVersion" -> quote("native"),
        "warmupIterations" -> c.warmupIterations.toString,
        "warmupTime" -> quote(s"${c.warmupNanos} ns"),
        "warmupBatchSize" -> c.warmupBatchSize.toString,
        "measurementIterations" -> c.measurementIterations.toString,
        "measurementTime" -> quote(s"${c.measurementNanos} ns"),
        "measurementBatchSize" -> c.measurementBatchSize.toString,
        "params" -> obj(run.parameters.toVector.sortBy(_._1).map { case (k, v) =>
          k -> quote(v)
        }),
        "primaryMetric" -> obj(metric),
        "secondaryMetrics" -> "{}",
        "native" -> nativeMetadata
      )
    )
  })
  def csv(results: Vector[RunResult]): String = {
    def cell(v: String): String = "\"" + v.replace("\"", "\"\"") + "\""
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
        r.statistics.count.toString,
        r.statistics.mean.toString,
        r.statistics.error.toString,
        r.unit
      ) ++ names.map(r.parameters.getOrElse(_, ""))
    }
    (Vector(header) ++ rows)
      .map(_.map(cell).mkString(","))
      .mkString("\n") + "\n"
  }
  def console(run: RunResult): Unit = {
    val s = run.statistics
    println(
      f"${run.descriptor.name}%-48s ${run.mode.shortLabel()}%6s ${s.count}%6d ${s.mean}%14.4f +/- ${s.error}%10.4f ${run.unit} ${run.parameters.toVector.sorted.mkString(" ")}"
    )
    if (run.mode == Mode.SampleTime || run.mode == Mode.SingleShotTime)
      println(
        Vector(50.0, 90.0, 99.0, 99.9)
          .map(p => f"p$p%.1f=${s.percentile(p)}%.4f")
          .mkString("  ")
      )
  }
}
