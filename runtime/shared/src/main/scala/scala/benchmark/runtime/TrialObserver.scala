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

/** Genuine execution events. Indices are one-based within warmup or measurement.
  *
  * The coordinator invokes observers outside benchmark work. The final result follows successful trial teardown and
  * counter capture. Warmup observations are events only; they never enter the returned measurement population.
  */
trait TrialObserver:
  def iteration(warmup: Boolean, index: Int): Unit
  def iterationResult(warmup: Boolean, index: Int, result: IterationResult): Unit

object TrialObserver:

  object Disabled extends TrialObserver:
    def iteration(warmup: Boolean, index: Int): Unit = ()
    def iterationResult(warmup: Boolean, index: Int, result: IterationResult): Unit = ()

  private[runtime] def invoke(body: => Unit): Unit = try body
  catch
    case error: InterruptedException => throw error
    case error: TrialObserverFailure => throw error
    case error: Throwable            => throw new TrialObserverFailure(error)

  private[runtime] def result(
      mode: org.openjdk.jmh.annotations.Mode,
      configuration: Configuration,
      warmup: Boolean,
      workers: Vector[RawResult]
  ): IterationResult =
    val scoring =
      if warmup then configuration.copy(measurementBatchSize = configuration.warmupBatchSize) else configuration
    IterationResult(Execution.aggregate(mode, scoring, workers), workers)

/** Callback failures are infrastructure failures, independently of benchmark fail-on-error policy. */
final class TrialObserverFailure(cause: Throwable)
    extends IllegalStateException("Benchmark execution observer failed: " + cause.getMessage, cause)
