package scala.scalanative.jmh

/** An embedded trial cannot safely continue while benchmark workers remain alive. */
private[jmh] final class UnfinishedWorkersException(val workers: Vector[String], cause: Throwable)
    extends IllegalStateException("Benchmark workers did not terminate: " + workers.mkString(", "), cause)
