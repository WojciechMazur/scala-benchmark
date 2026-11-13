package scala.scalanative.jmh

/** Fixture arbitration needs no locks when a single event loop owns all state. */
final class StateSlot(val instance: AnyRef, val shared: Boolean) {
  private var failure: Throwable = null
  private val ready = Array(false, false, false)
  def enter(level: Int, setup: Boolean): Boolean = {
    if (failure != null) throw failure
    !shared || ready(level) != setup
  }
  def fail(error: Throwable): Unit = if (shared) failure = error
  def leave(level: Int, setup: Boolean, success: Boolean): Unit =
    if (shared && success) ready(level) = setup
}
