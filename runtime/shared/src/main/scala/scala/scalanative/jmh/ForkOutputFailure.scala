package scala.scalanative.jmh

/** A blocked output channel cannot safely carry further benchmark diagnostics. */
private[jmh] final class ForkOutputException(message: String, cause: Throwable = null)
    extends IllegalStateException(message, cause)

private[jmh] object ForkOutputFailure {
  @volatile var failed: Boolean = false
  def fail(message: String, cause: Throwable = null): ForkOutputException = {
    failed = true
    new ForkOutputException(message, cause)
  }
  def check(): Unit = if (failed) throw new ForkOutputException("Benchmark output channel is unusable")
}
