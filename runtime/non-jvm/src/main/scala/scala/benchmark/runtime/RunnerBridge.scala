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

import scala.benchmark.internal.{RunnerService, Selection}
import scala.jdk.CollectionConverters.*

import org.openjdk.jmh.runner.{NoBenchmarksException, RunnerException}
import org.openjdk.jmh.runner.options.{Options as PublicOptions, OptionsBuilder, VerboseMode, WarmupMode}
import org.openjdk.jmh.util.Optional

/** Interface/runtime dependency boundary; user benchmark classes are never reflected. */
object RunnerBridge extends RunnerService:

  def renderResults(
      results: java.util.Collection[org.openjdk.jmh.results.RunResult],
      format: org.openjdk.jmh.results.format.ResultFormatType
  ): String =
    if format == null then throw new NullPointerException("format")
    if results == null then throw new NullPointerException("results")
    Reports.render(
      results.asScala.toVector.map(PublicReportResults.apply),
      scala.benchmark.internal.Ascii.lower(format.name())
    )

  def writeResultsFile(
      results: java.util.Collection[org.openjdk.jmh.results.RunResult],
      format: org.openjdk.jmh.results.format.ResultFormatType,
      file: String
  ): Unit =
    def write(text: String): Unit =
      if file == null then throw new NullPointerException("file")
      try Platform.write(file, text)
      catch
        case scala.util.control.NonFatal(error) => throw new IllegalStateException(error)
    // JMH opens/truncates the file lazily, before selecting and rendering the format.
    write("")
    write(renderResults(results, format))

  private def inventory: Vector[Descriptor] = Platform.inventory.distinct.sorted.flatMap { name =>
    Platform.loadSuite(name).metadata().toVector.zipWithIndex.map { case (text, index) =>
      Descriptor.of(index, text)
    }
  }

  private def translated(options: PublicOptions, listing: Boolean = false, parameters: Boolean = false): Options =
    val discovered = inventory
    val includes = options.getIncludes().asScala.toVector
    val excludes = options.getExcludes().asScala.toVector
    val selected = Selection(discovered, includes, excludes, Platform.identity, listing)(_.name, _.eligibility)
    if !listing && selected.selected.nonEmpty then
      require(
        !options.getJvm().hasValue() &&
          Vector(options.getJvmArgs(), options.getJvmArgsAppend(), options.getJvmArgsPrepend())
            .forall(v => !v.hasValue() || v.get().isEmpty()),
        "JVM arguments do not configure Native or Node.js; use platform build/host settings"
      )
    var overrides = Map.empty[String, String]
    def add[T](key: String, value: Optional[T])(render: T => String): Unit =
      if value.hasValue() then overrides += key -> render(value.get())
    add("-wi", options.getWarmupIterations())(_.toString)
    add("-i", options.getMeasurementIterations())(_.toString)
    add("-wbs", options.getWarmupBatchSize())(_.toString)
    add("-bs", options.getMeasurementBatchSize())(_.toString)
    add("-f", options.getForkCount())(_.toString)
    add("-wf", options.getWarmupForkCount())(_.toString)
    add("-t", options.getThreads())(_.toString)
    add("-opi", options.getOperationsPerInvocation())(_.toString)
    add("-si", options.shouldSyncIterations())(_.toString)
    add("-gc", options.shouldDoGC())(_.toString)
    add("-tu", options.getTimeUnit())(_.name())
    add("-tg", options.getThreadGroups())(_.mkString(","))
    add("-to", options.getTimeout())(t => t.convertTo(java.util.concurrent.TimeUnit.NANOSECONDS).toString + "ns")
    add("-w", options.getWarmupTime())(t => t.convertTo(java.util.concurrent.TimeUnit.NANOSECONDS).toString + "ns")
    add("-r", options.getMeasurementTime())(t => t.convertTo(java.util.concurrent.TimeUnit.NANOSECONDS).toString + "ns")
    Vector("-w" -> options.getWarmupTime(), "-r" -> options.getMeasurementTime(), "-to" -> options.getTimeout())
      .foreach {
        case (key, time) if time.hasValue() =>
          val value = time.get()
          overrides += (key + ".time") -> value.getTime().toString
          overrides += (key + ".unit") -> value.getTimeUnit().name()
        case _ => ()
      }
    val known = discovered.flatMap(_.states.flatMap(_.parameters.map(_.name))) ++
      (options match
        case builder: OptionsBuilder => builder.parameterNames()
        case _                       => Vector.empty)
    val parameterValues = known.distinct.flatMap { name =>
      val values = options.getParameter(name)
      if values.hasValue() then Some(name -> values.get().asScala.toVector) else None
    }.toMap
    val format = scala.benchmark.internal.Ascii
      .lower(options.getResultFormat().orElse(org.openjdk.jmh.results.format.ResultFormatType.CSV).name())
    val file =
      if options.getResult().hasValue() then Some(options.getResult().get())
      else if options.getResultFormat().hasValue() then Some("jmh-result." + format)
      else None
    Options(
      includes = includes,
      excludes = excludes,
      overrides = overrides,
      parameters = parameterValues,
      modes = options.getBenchModes().asScala.toVector,
      list = listing,
      listParameters = parameters,
      format = format,
      output = file,
      failOnError = options.shouldFailOnError().orElse(false),
      consoleOutput = if options.getOutput().hasValue() then Some(options.getOutput().get()) else None,
      verbosity = options.verbosity().orElse(VerboseMode.NORMAL).name(),
      warmupMode = options.getWarmupMode().orElse(WarmupMode.INDI).name(),
      warmupIncludes = options.getWarmupIncludes().asScala.toVector
    )

  end translated

  private def checked[A](body: => A): A = try body
  catch
    case error: RunnerException => throw error
    case error: IllegalArgumentException
        if Option(error.getMessage).exists(_.contains("No benchmarks match the selection")) =>
      throw new NoBenchmarksException
    case error: Throwable => throw new RunnerException(error.getMessage, error)

  def run(
      options: PublicOptions,
      format: org.openjdk.jmh.runner.format.OutputFormat
  ): java.util.Collection[org.openjdk.jmh.results.RunResult] = checked {
    val resolved = try translated(options)
    catch
      case error: IllegalArgumentException
          if Option(error.getMessage).exists(_.contains("No benchmarks match the selection")) =>
        if format != null then
          TrialObserver.invoke(format.flush()); TrialObserver.invoke(format.close())
        throw error
    val observer = FormatObserver(format)
    val completed = BenchmarkRunner.run(resolved, observer)
    observer match
      case custom: FormatObserver => custom.results
      case _                      => completed.map(PublicResults.apply).asJava
  }

  def runSingle(
      options: PublicOptions,
      format: org.openjdk.jmh.runner.format.OutputFormat
  ): org.openjdk.jmh.results.RunResult = checked {
    val translatedOptions = translated(options)
    val selected = Selection(inventory, translatedOptions.includes, translatedOptions.excludes, Platform.identity)(
      _.name,
      _.eligibility
    ).selected
    // JMH counts the original inventory entries before applying requested mode overrides.
    val entries = selected.flatMap(_.modes)
    require(
      entries.size == 1,
      "Expected a single benchmark and mode"
    )
    val observer = FormatObserver(format)
    val completed = BenchmarkRunner.run(translatedOptions, observer)
    val results = observer match
      case custom: FormatObserver => custom.results
      case _                      => completed.map(PublicResults.apply).asJava
    if results.size() != 1 then throw new RunnerException("No results returned")
    results.iterator().next()
  }

  def list(options: PublicOptions, parameters: Boolean, format: org.openjdk.jmh.runner.format.OutputFormat): Unit =
    checked {
      BenchmarkRunner.run(translated(options, listing = true, parameters = parameters), FormatObserver(format)); ()
    }

end RunnerBridge
