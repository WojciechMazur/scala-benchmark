package scala.benchmark.runtime

/** A blocked output channel cannot safely carry further benchmark diagnostics. */
final private[runtime] class ForkOutputException(message: String, cause: Throwable = null)
    extends IllegalStateException(message, cause)

private[runtime] object ForkOutputFailure:
  @volatile var failed: Boolean = false

  def fail(message: String, cause: Throwable = null): ForkOutputException =
    failed = true
    new ForkOutputException(message, cause)

  def check(): Unit = if failed then throw new ForkOutputException("Benchmark output channel is unusable")
