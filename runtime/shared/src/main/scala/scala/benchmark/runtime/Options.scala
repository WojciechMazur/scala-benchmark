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
package scala.benchmark.runtime

import java.util.concurrent.TimeUnit

import scala.benchmark.internal.Ascii

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
    format: String = "csv",
    output: Option[String] = None,
    failOnError: Boolean = false,
    listFormats: Boolean = false,
    consoleOutput: Option[String] = None,
    verbosity: String = "NORMAL",
    warmupMode: String = "INDI",
    warmupIncludes: Vector[String] = Vector.empty,
    jvm: Option[String] = None,
    jvmArgs: Option[Vector[String]] = None,
    jvmArgsAppend: Vector[String] = Vector.empty,
    jvmArgsPrepend: Vector[String] = Vector.empty
):

  def configuration(default: Configuration): Configuration =
    def int(key: String, fallback: Int): Int =
      overrides.get(key).fold(fallback)(_.toInt)
    def time(key: String, fallback: Long): Long =
      overrides.get(key).fold(fallback)(Options.duration)
    def originalTime(
        key: String,
        fallback: org.openjdk.jmh.runner.options.TimeValue
    ): org.openjdk.jmh.runner.options.TimeValue =
      if overrides.contains(key + ".time") then
        new org.openjdk.jmh.runner.options.TimeValue(
          overrides(key + ".time").toLong,
          TimeUnit.valueOf(overrides(key + ".unit"))
        )
      else
        overrides
          .get(key)
          .map { value =>
            try org.openjdk.jmh.runner.options.TimeValue.fromString(value)
            catch
              case _: NumberFormatException =>
                org.openjdk.jmh.runner.options.TimeValue.nanoseconds(Options.duration(value))
          }
          .getOrElse(fallback)
    val threads = overrides.get("-t").fold(default.threads) {
      case value if value.equalsIgnoreCase("max") => -1
      case value                                  => value.toInt
    } match
      case -1 => Platform.processorCount
      case -2 => math.max(1, Platform.processorCount / 2)
      case n  => n
    val c = default.copy(
      warmupIterations = int("-wi", default.warmupIterations),
      measurementIterations = int("-i", default.measurementIterations),
      warmupNanos = time("-w", default.warmupNanos),
      measurementNanos = time("-r", default.measurementNanos),
      warmupTime = originalTime("-w", default.warmupTime),
      measurementTime = originalTime("-r", default.measurementTime),
      warmupBatchSize = int("-wbs", default.warmupBatchSize),
      measurementBatchSize = int("-bs", default.measurementBatchSize),
      forks = int("-f", default.forks),
      warmupForks = int("-wf", default.warmupForks),
      threads = threads,
      timeoutNanos = time("-to", default.timeoutNanos),
      timeoutTime = originalTime("-to", default.timeoutTime),
      operationsPerInvocation = int("-opi", default.operationsPerInvocation),
      synchIterations = overrides.get("-si").fold(default.synchIterations)(Values.boolean),
      doGC = overrides.get("-gc").fold(default.doGC)(Values.boolean),
      threadGroups = overrides.get("-tg").fold(default.threadGroups)(_.split(",", -1).toVector.map(_.toInt)),
      unit = overrides.get("-tu").fold(default.unit)(Options.unit),
      parameterOrder = default.parameterOrder ++ overrides.collect {
        case (key, value) if key.startsWith("-parameterOrder.") => key.stripPrefix("-parameterOrder.") -> value.toInt
      }
    )
    require(c.parameterOrder.values.forall(_ >= 0), "Invalid parameter ordinal")
    require(
      c.threadGroups.isEmpty || (c.threadGroups.forall(_ >= 0) && c.threadGroups.exists(_ > 0)),
      "Thread groups must be nonnegative with at least one worker"
    )
    require(
      c.warmupIterations >= 0 && c.measurementIterations > 0 && c.forks >= 0 && c.warmupForks >= 0,
      "Invalid iteration or fork count"
    )
    require(
      c.warmupBatchSize > 0 && c.measurementBatchSize > 0 && c.threads > 0 && c.operationsPerInvocation > 0,
      "Batch sizes, thread count and operations per invocation must be positive"
    )
    require(
      c.timeoutNanos >= 0 && c.warmupNanos >= 0 && c.measurementNanos >= 0,
      "Durations must be nonnegative"
    )
    c

  end configuration

end Options

object Options:

  /** A time override: nanoseconds for the configuration, with the given value and unit kept for reports. */
  private[runtime] def timeOverrides(
      key: String,
      value: org.openjdk.jmh.runner.options.TimeValue
  ): Map[String, String] =
    Map(
      key -> (value.convertTo(TimeUnit.NANOSECONDS).toString + "ns"),
      (key + ".time") -> value.getTime().toString,
      (key + ".unit") -> value.getTimeUnit().name()
    )

  val help =
    s"""Scala Benchmark (JMH 1.37 source compatibility)
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
      | -opi <n>          Operations represented by each invocation
      | -si true|false    Synchronize worker measurement boundaries
      | -tg n1,n2,...     Workers per group member; zero disables a member
      | -gc true|false    Request host collection between iterations
      | -wm INDI|BULK|BULK_INDI  Warmup policy
      | -wmb <regex>       Additional warmup-only benchmark filters
      | -v SILENT|NORMAL|EXTRA  Human-readable output detail
      | -o <path>          Human-readable output file
      | -lrf              List result formats
      | -e <regex>        Exclude benchmarks
      | -rf <format>      Result format: ${Reports.formats.mkString(", ")}
      | -rff <path>       Result file
      | -foe true|false   Stop after a failed benchmark
      | --suite <object>  Also run a hand-written suite object
      | -jvm <path>       JVM backend: Java executable of the forks
      | -jvmArgs <args>   JVM backend: replace the forks' inherited JVM options
      | -jvmArgsAppend <args>   JVM backend: append JVM options to the forks
      | -jvmArgsPrepend <args>  JVM backend: prepend JVM options to the forks
      | -h                Show this help
      |""".stripMargin

  def mode(value: String): Mode = Mode.deepValueOf(value)

  def unit(value: String): String = value match
    case "ns" => "NANOSECONDS"
    case "us" => "MICROSECONDS"
    case "ms" => "MILLISECONDS"
    case "s"  => "SECONDS"
    case "m"  => "MINUTES"
    case "h"  => "HOURS"
    case "d"  => "DAYS"
    case v    => TimeUnit.valueOf(v).name()

  private def cliUnit(value: String): String =
    val normalized = Ascii.lower(value)
    require(Set("ns", "us", "ms", "s", "m", "h")(normalized), s"Unknown time unit: $value")
    unit(normalized)

  def duration(value: String): Long =
    val split = value.indexWhere(c => !c.isDigit && c != '.')
    val (number, suffix) = if split < 0 then (value, "s") else value.splitAt(split)
    val count = number.toLong
    require(count >= 0, s"Invalid duration: $value")
    TimeUnit.valueOf(unit(suffix)).toNanos(count)

  def parse(args: Array[String]): Options =
    var options = Options()
    val scalars = scala.collection.mutable.Set.empty[String]
    var formatRequested = false
    var i = 0
    while i < args.length do
      val key = args(i)
      def argument(): String =
        i += 1; require(i < args.length, s"Missing argument for $key"); args(i)
      if key.startsWith("-") && !Set("-e", "-p", "-bm", "-wmb", "--suite", "-l", "-lp", "-lrf", "-h", "--help")(key)
      then
        require(scalars.add(key), s"Repeated option: $key")
      key match
        case "-h" | "--help" => options = options.copy(help = true)
        case "-wm"           =>
          val value = argument()
          require(Set("INDI", "BULK", "BULK_INDI")(value), "Expected INDI, BULK or BULK_INDI warmup mode")
          options = options.copy(warmupMode = value)
        case "-wmb" => options = options.copy(warmupIncludes = options.warmupIncludes ++ argument().split(",").toVector)
        case "-o"   => options = options.copy(consoleOutput = Some(argument()))
        case "-v"   =>
          val value = argument()
          require(Set("SILENT", "NORMAL", "EXTRA")(value), "Expected SILENT, NORMAL or EXTRA verbosity")
          options = options.copy(verbosity = value)
        case "-lrf"    => options = options.copy(listFormats = true)
        case "-l"      => options = options.copy(list = true)
        case "-lp"     => options = options.copy(list = true, listParameters = true)
        case "--suite" =>
          options = options.copy(suites = options.suites :+ argument())
        case "-e" =>
          options = options.copy(excludes = options.excludes ++ argument().split(",").toVector)
        case "-bm" =>
          options = options.copy(modes = (options.modes ++ argument().split(",").toVector.map(mode)).distinct)
        case "-p" =>
          val parameter = argument().split("=", 2)
          require(
            parameter.length == 2 && parameter(0).nonEmpty,
            "Expected -p name=value[,value]"
          )
          options = options.copy(parameters =
            options.parameters
              .updated(
                parameter(0),
                options.parameters.getOrElse(parameter(0), Vector.empty) ++ parameter(1).split(",", -1).toVector
              )
          )
        case "-rf" =>
          formatRequested = true
          val format = Ascii.lower(argument())
          require(
            Reports.formats.contains(format),
            "Supported formats are " + Reports.formats.mkString(", ")
          )
          options = options.copy(format = format)
        case "-rff"        => options = options.copy(output = Some(argument()))
        case "-foe"        => options = options.copy(failOnError = java.lang.Boolean.parseBoolean(argument()))
        case "-si" | "-gc" =>
          options = options.copy(overrides =
            options.overrides.updated(key, java.lang.Boolean.parseBoolean(argument()).toString)
          )
        case "-w" | "-r" | "-to" =>
          val value = org.openjdk.jmh.runner.options.TimeValue.fromString(argument())
          options = options.copy(overrides = options.overrides ++ timeOverrides(key, value))
        case "-t" =>
          val value = argument()
          require(value.equalsIgnoreCase("max") || value.toInt > 0, "Thread count must be positive or max")
          options = options.copy(overrides = options.overrides.updated(key, value))
        case "-tu" => options = options.copy(overrides = options.overrides.updated(key, cliUnit(argument())))
        case "-wi" | "-i" | "-wbs" | "-bs" | "-f" | "-wf" | "-opi" | "-tg" =>
          options = options.copy(overrides = options.overrides.updated(key, argument()))
        case "-jvm" | "-jvmArgs" | "-jvmArgsAppend" | "-jvmArgsPrepend" =>
          require(Platform.identity == scala.benchmark.Platform.JVM, s"$key only applies to the JVM backend")
          val value = argument()
          val words = value.trim.split("\\s+").toVector.filter(_.nonEmpty)
          options = key match
            case "-jvm"           => options.copy(jvm = Some(value))
            case "-jvmArgs"       => options.copy(jvmArgs = Some(words))
            case "-jvmArgsAppend" => options.copy(jvmArgsAppend = words)
            case _                => options.copy(jvmArgsPrepend = words)
        case v if v.startsWith("-") =>
          throw new IllegalArgumentException(
            s"Unsupported benchmark option: $v"
          )
        case regex =>
          options = options.copy(includes = options.includes :+ regex)
      end match
      i += 1
    end while
    options.configuration(Configuration())
    if formatRequested && options.output.isEmpty then options.copy(output = Some("jmh-result." + options.format))
    else options

  end parse

end Options
