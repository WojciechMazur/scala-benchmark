package scala.benchmark.runtime

/** State storage and fixture arbitration are deliberately separate from user state.
  */
final class StateSlot(val instance: AnyRef, val shared: Boolean) {
  @volatile private var failure: Throwable = null
  private val ready = Array(false, false, false)
  private val locks =
    Array.fill(3)(new java.util.concurrent.locks.ReentrantLock)
  def enter(level: Int, setup: Boolean): Boolean = {
    if (!shared) true
    else {
      locks(level).lockInterruptibly()
      if (failure != null) { locks(level).unlock(); throw failure }
      if (ready(level) != setup) true
      else { locks(level).unlock(); false }
    }
  }
  def fail(error: Throwable): Unit = if (shared) failure = error
  def leave(level: Int, setup: Boolean, success: Boolean): Unit = {
    if (shared) {
      if (success) ready(level) = setup
      locks(level).unlock()
    }
  }
}
