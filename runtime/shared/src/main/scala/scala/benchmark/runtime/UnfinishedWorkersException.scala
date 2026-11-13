package scala.benchmark.runtime

/** An embedded trial cannot safely continue while benchmark workers remain alive. */
private[runtime] final class UnfinishedWorkersException(val workers: Vector[String], cause: Throwable)
    extends IllegalStateException("Benchmark workers did not terminate: " + workers.mkString(", "), cause)
