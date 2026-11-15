/*
 * Copyright (c) 2026, Wojciech Mazur.
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

/** Explicit benchmark failure is distinct from process/protocol failure. */
final private[runtime] class BenchmarkForkFailure(
    val benchmarkType: String,
    message: String,
    val completed: Vector[IterationResult]
) extends IllegalStateException(s"$benchmarkType: $message")

final private[runtime] class CompletedIterations(val completed: Vector[IterationResult])
    extends RuntimeException("Completed measurement iterations preceding the failed iteration")

private[runtime] object BenchmarkForkFailure:

  def completed(error: Throwable): Vector[IterationResult] = error.getSuppressed
    .collectFirst { case prior: CompletedIterations =>
      prior.completed
    }
    .getOrElse(Vector.empty)

  def verify(failure: BenchmarkForkFailure, mode: org.openjdk.jmh.annotations.Mode, config: Configuration): Nothing =
    require(failure.completed.size < config.measurementIterations, "Invalid failed-fork observation count")
    require(failure.completed.forall(_.trial.isEmpty), "A failed fork cannot declare completed trial metadata")
    if failure.completed.nonEmpty then
      ForkResults.verify(
        mode,
        config.copy(measurementIterations = failure.completed.size),
        failure.completed,
        requireTrialMetadata = false
      )
    throw failure

/** JMH 1.37 aborts embedded late failures because completed trial metadata is absent. */
final private[runtime] class IncompleteEmbeddedTrialException(cause: Throwable)
    extends IllegalStateException(
      "Embedded benchmark failed after completed iterations; no complete trial result is available",
      cause
    )
