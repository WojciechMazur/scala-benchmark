package scala.scalanative.jmh

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.Mode

final case class Options(
    includes: Vector[String] = Vector.empty,
    excludes: Vector[String] = Vector.empty,
    suites: Vector[String] = Vector.empty,
    overrides: Map[String, String] = Map.empty,
    parameters: Map[String, Vector[String]] = Map.empty,
    modes: Vector[Mode] = Vector.empty,
    list: Boolean = false,
    listParameters: Boolean = false,
    help: Boolean = false,
    format: String = "json",
    output: Option[String] = None,
    failOnError: Boolean = false
) {
  def configuration(default: Configuration): Configuration = {
    def int(key: String, fallback: Int): Int =
      overrides.get(key).fold(fallback)(_.toInt)
    def time(key: String, fallback: Long): Long =
      overrides.get(key).fold(fallback)(Options.duration)
    val threads = int("-t", default.threads) match {
      case -1 => Runtime.getRuntime().availableProcessors()
      case -2 => math.max(1, Runtime.getRuntime().availableProcessors() / 2)
      case n  => n
    }
    val c = default.copy(
      warmupIterations = int("-wi", default.warmupIterations),
      measurementIterations = int("-i", default.measurementIterations),
      warmupNanos = time("-w", default.warmupNanos),
      measurementNanos = time("-r", default.measurementNanos),
      warmupBatchSize = int("-wbs", default.warmupBatchSize),
      measurementBatchSize = int("-bs", default.measurementBatchSize),
      forks = int("-f", default.forks),
      warmupForks = int("-wf", default.warmupForks),
      threads = threads,
      timeoutNanos = time("-to", default.timeoutNanos),
      unit = overrides.get("-tu").fold(default.unit)(Options.unit)
    )
    require(
      c.warmupIterations >= 0 && c.measurementIterations > 0 && c.forks >= 0 && c.warmupForks >= 0,
      "Invalid iteration or fork count"
    )
    require(
      c.warmupBatchSize > 0 && c.measurementBatchSize > 0 && c.threads > 0,
      "Batch sizes and thread count must be positive"
    )
    require(
      c.timeoutNanos > 0 && c.warmupNanos > 0 && c.measurementNanos > 0,
      "Durations must be positive"
    )
    c
  }
}

object Options {
  val help =
    """Native JMH (JMH 1.37 source compatibility)
      |Usage: benchmark [regex] [options]
      | -l / -lp          List benchmarks / parameters
      | -bm <modes>       thrpt,avgt,sample,ss,all
      | -wi / -i <n>      Warmup / measurement iterations
      | -w / -r <time>    Warmup / measurement duration (e.g. 100ms, 1s)
      | -wbs / -bs <n>    Warmup / measurement batch size
      | -f / -wf <n>      Measurement / discarded warmup forks
      | -t <n>            Threads; -1 all processors, -2 half
      | -p name=v1,v2     Override parameter values
      | -tu ns|us|ms|s    Output time unit
      | -to <time>        Iteration timeout
      | -e <regex>        Exclude benchmarks
      | -rf json|csv      Result file format
      | -rff <path>       Result file
      | -foe true|false   Stop after a failed benchmark
      | --suite <module>  Explicit generated suite
      | -h                Show this help
      |""".stripMargin
  def mode(value: String): Mode = value match {
    case "thrpt" | "Throughput"  => Mode.Throughput
    case "avgt" | "AverageTime"  => Mode.AverageTime
    case "sample" | "SampleTime" => Mode.SampleTime
    case "ss" | "SingleShotTime" => Mode.SingleShotTime
    case "all" | "All"           => Mode.All
    case _                       =>
      throw new IllegalArgumentException(s"Unknown benchmark mode: $value")
  }
  def unit(value: String): String = value match {
    case "ns" => "NANOSECONDS"
    case "us" => "MICROSECONDS"
    case "ms" => "MILLISECONDS"
    case "s"  => "SECONDS"
    case "m"  => "MINUTES"
    case "h"  => "HOURS"
    case "d"  => "DAYS"
    case v    => TimeUnit.valueOf(v).name()
  }
  def duration(value: String): Long = {
    val split = value.indexWhere(c => !c.isDigit && c != '.')
    val (number, suffix) = if (split < 0) (value, "s") else value.splitAt(split)
    val result = number.toDouble * TimeUnit.valueOf(unit(suffix)).toNanos(1)
    require(
      result > 0 && result <= Long.MaxValue.toDouble && result.isFinite,
      s"Invalid duration: $value"
    )
    result.toLong
  }
  def parse(args: Array[String]): Options = {
    var options = Options()
    var i = 0
    while (i < args.length) {
      val key = args(i)
      def argument(): String = {
        i += 1; require(i < args.length, s"Missing argument for $key"); args(i)
      }
      key match {
        case "-h" | "--help" => options = options.copy(help = true)
        case "-l"            => options = options.copy(list = true)
        case "-lp"           => options = options.copy(list = true, listParameters = true)
        case "--suite"       =>
          options = options.copy(suites = options.suites :+ argument())
        case "-e" =>
          options = options.copy(excludes = options.excludes :+ argument())
        case "-bm" =>
          options = options.copy(modes = argument().split(",").toVector.map(mode))
        case "-p" =>
          val parameter = argument().split("=", 2)
          require(
            parameter.length == 2 && parameter(0).nonEmpty,
            "Expected -p name=value[,value]"
          )
          options = options.copy(parameters =
            options.parameters
              .updated(parameter(0), parameter(1).split(",", -1).toVector)
          )
        case "-rf" =>
          val format = argument();
          require(
            Set("json", "csv")(format),
            "Supported formats are json and csv"
          )
          options = options.copy(format = format)
        case "-rff" => options = options.copy(output = Some(argument()))
        case "-foe" =>
          val value = argument();
          require(value == "true" || value == "false", "Expected true or false")
          options = options.copy(failOnError = value.toBoolean)
        case "-wi" | "-i" | "-w" | "-r" | "-wbs" | "-bs" | "-f" | "-wf" | "-t" | "-to" | "-tu" =>
          options = options.copy(overrides = options.overrides.updated(key, argument()))
        case v if v.startsWith("-") =>
          throw new IllegalArgumentException(
            s"Unsupported Native JMH option: $v"
          )
        case regex =>
          options = options.copy(includes = options.includes :+ regex)
      }
      i += 1
    }
    options
  }
}
