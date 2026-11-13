package scala.scalanative.jmh

/** Explicit benchmark failure is distinct from process/protocol failure. */
private[jmh] final class BenchmarkForkFailure(
    val benchmarkType: String,
    message: String,
    val completed: Vector[IterationResult]
) extends IllegalStateException(s"$benchmarkType: $message")

private[jmh] final class CompletedIterations(val completed: Vector[IterationResult])
    extends RuntimeException("Completed measurement iterations preceding the failed iteration")

private[jmh] object BenchmarkForkFailure {
  def completed(error: Throwable): Vector[IterationResult] = error.getSuppressed
    .collectFirst { case prior: CompletedIterations =>
      prior.completed
    }
    .getOrElse(Vector.empty)
  def verify(failure: BenchmarkForkFailure, mode: org.openjdk.jmh.annotations.Mode, config: Configuration): Nothing = {
    require(failure.completed.size < config.measurementIterations, "Invalid failed-fork observation count")
    require(failure.completed.forall(_.trial.isEmpty), "A failed fork cannot declare completed trial metadata")
    if (failure.completed.nonEmpty)
      ForkResults.verify(
        mode,
        config.copy(measurementIterations = failure.completed.size),
        failure.completed,
        requireTrialMetadata = false
      )
    throw failure
  }
}

/** JMH 1.37 aborts embedded late failures because completed trial metadata is absent. */
private[jmh] final class IncompleteEmbeddedTrialException(cause: Throwable)
    extends IllegalStateException(
      "Embedded benchmark failed after completed iterations; no complete trial result is available",
      cause
    )
