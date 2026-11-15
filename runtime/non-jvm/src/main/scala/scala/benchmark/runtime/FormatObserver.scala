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

import java.io.{OutputStream, PrintStream}

import scala.jdk.CollectionConverters.*

import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.runner.format.OutputFormat

private[runtime] object FormatObserver:
  def apply(sink: OutputFormat): RunObserver = if sink == null then RunObserver.Disabled else new FormatObserver(sink)

final private[runtime] class FormatObserver(sink: OutputFormat) extends RunObserver:
  override def displaysResults: Boolean = true

  override def diagnosticOutput: PrintStream = new PrintStream(
    new OutputStream:
      def write(value: Int): Unit = invoke(sink.write(value))
      override def write(bytes: Array[Byte], offset: Int, length: Int): Unit =
        invoke(sink.write(bytes.slice(offset, offset + length)))
    ,
    true,
    "UTF-8"
  )

  private var ended = false
  private val cache = new java.util.IdentityHashMap[RunResult, org.openjdk.jmh.results.RunResult]

  private val trials = scala.collection.mutable.ArrayBuffer.empty[
    (
        org.openjdk.jmh.infra.BenchmarkParams,
        scala.collection.mutable.ArrayBuffer[org.openjdk.jmh.results.IterationResult]
    )
  ]

  var results: java.util.Collection[org.openjdk.jmh.results.RunResult] = java.util.Collections.emptyList()

  private def converted(run: RunResult): org.openjdk.jmh.results.RunResult =
    val existing = cache.get(run)
    if existing != null then existing
    else
      val result = PublicResults(run)
      val observations = trials
        .filter { case (params, values) => params == result.getParams() && values.nonEmpty }
        .takeRight(result.getBenchmarkResults().size())
      require(observations.size == result.getBenchmarkResults().size(), "Missing observed measurement trials")
      result.getBenchmarkResults().asScala.zip(observations).foreach { case (benchmark, (_, iterations)) =>
        require(benchmark.getIterationResults().size() == iterations.size, "Incomplete observed measurement trial")
        benchmark.getIterationResults().clear()
        benchmark.getIterationResults().addAll(iterations.asJava)
      }
      cache.put(run, result)
      result

  /** Report snapshots contain public observations, never invented worker timing data. */
  override def reportResults(completed: Vector[RunResult]): Vector[RunResult] =
    val selected = if ended then results.asScala.toVector else completed.map(converted)
    selected.map { result =>
      val context = completed
        .find(run => cache.get(run) eq result)
        .getOrElse(throw new IllegalArgumentException("Custom output supplied results for an unknown benchmark"))
      PublicReportResults(result, context)
    }

  private def invoke(body: => Unit): Unit = try TrialObserver.invoke(body)
  catch
    case error: InterruptedException =>
      Platform.restoreCallerInterrupt()
      throw new TrialObserverFailure(error)

  override def output[A](options: Options)(body: => A): A =
    val stream = new PrintStream(new OutputStream:
      def write(value: Int): Unit = invoke(sink.write(value))):
      override def write(value: Int): Unit = invoke(sink.write(value))
      override def write(bytes: Array[Byte], offset: Int, length: Int): Unit =
        invoke(sink.write(bytes.slice(offset, offset + length)))
      override def print(text: String): Unit = invoke(sink.print(text))
      override def print(value: Any): Unit = invoke(sink.print(if value == null then "null" else value.toString))
      override def println(value: Any): Unit = invoke(sink.println(if value == null then "null" else value.toString))
      override def println(text: String): Unit = invoke(sink.println(text))
      override def println(): Unit = invoke(sink.println(""))
      // Runner lifecycle owns flush/close, including the worker-forwarded calls.
      override def flush(): Unit = ()
      override def close(): Unit = ()
    Console.withOut(stream)(Console.withErr(stream)(body))

  override def startRun(): Unit = invoke(sink.startRun())

  override def startBenchmark(d: Descriptor, mode: Mode, c: Configuration, p: Map[String, String]): Unit =
    val descriptor = d.configured(c)
    val resolved = descriptor.resolve(c)
    invoke(
      sink.startBenchmark(
        Infrastructure.benchmark(
          descriptor,
          mode,
          if mode == Mode.SingleShotTime then resolved.copy(synchIterations = false) else resolved,
          p
        )
      )
    )

  override def trial(d: Descriptor, mode: Mode, c: Configuration, p: Map[String, String]): TrialObserver =
    val descriptor = d.configured(c)
    val resolved = descriptor.resolve(c)
    val actual = if mode == Mode.SingleShotTime then resolved.copy(synchIterations = false) else resolved
    val params = Infrastructure.benchmark(descriptor, mode, actual, p)
    val measurements = scala.collection.mutable.ArrayBuffer.empty[org.openjdk.jmh.results.IterationResult]
    trials += params -> measurements
    new TrialObserver:
      private def iterationParams(warmup: Boolean) = if warmup then params.getWarmup() else params.getMeasurement()
      def iteration(warmup: Boolean, index: Int): Unit = invoke(sink.iteration(params, iterationParams(warmup), index))
      def iterationResult(warmup: Boolean, index: Int, result: IterationResult): Unit =
        val value = PublicResults.iteration(descriptor, mode, actual, p, warmup, result)
        invoke(sink.iterationResult(params, iterationParams(warmup), index, value))
        if !warmup then measurements += value

  override def endBenchmark(result: Option[RunResult], forked: Boolean): Unit = invoke {
    val publicResult = result.map(converted)
    sink.endBenchmark(
      publicResult.map(r =>
        if forked then r.getAggregatedResult() else r.getBenchmarkResults().iterator().next()
      ).orNull
    )
  }

  override def endRun(completed: Vector[RunResult]): Unit =
    val ordered = new java.util.TreeSet[org.openjdk.jmh.results.RunResult](
      org.openjdk.jmh.results.RunResult.DEFAULT_SORT_COMPARATOR
    )
    completed.foreach(run => ordered.add(converted(run)))
    results = ordered
    ended = true
    invoke(sink.endRun(results))

  override def flush(): Unit = invoke(sink.flush())
  override def close(): Unit = invoke(sink.close())

end FormatObserver
