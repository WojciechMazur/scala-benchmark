package scala.scalanative.jmh

import java.util.concurrent.TimeUnit

private[jmh] object ForkProcess {

  /** Preserve the original failure and caller interruption while bounding cleanup. */
  def managed[A](process: Process)(body: => A)(afterStop: => Unit): A = {
    var failure: Throwable = null
    var interrupted = false
    try body
    catch {
      case error: Throwable =>
        failure = error
        interrupted = error.isInstanceOf[InterruptedException]
        throw error
    } finally {
      interrupted = Thread.interrupted() || interrupted
      def cleanup(action: => Unit): Unit = try action
      catch {
        case error: Throwable =>
          if (error.isInstanceOf[InterruptedException]) interrupted = true
          if (error.isInstanceOf[UnfinishedWorkersException]) {
            val fatal =
              if (failure == null || (error.getCause eq failure)) error
              else if (failure.isInstanceOf[UnfinishedWorkersException]) { failure.addSuppressed(error); failure }
              else {
                val combined =
                  new UnfinishedWorkersException(error.asInstanceOf[UnfinishedWorkersException].workers, failure)
                combined.addSuppressed(error)
                combined
              }
            failure = fatal
            throw fatal
          } else if (failure != null) failure.addSuppressed(error)
          else { failure = error; throw error }
      }
      try {
        cleanup {
          if (process.isAlive) {
            process.destroyForcibly()
            val started = System.nanoTime()
            var stopped = false
            while (!stopped && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1)) {
              try stopped = process.waitFor(25, TimeUnit.MILLISECONDS)
              catch { case _: InterruptedException => interrupted = true }
            }
            if (!stopped && process.isAlive) throw WorkerContainment.quarantine(process, failure)
          }
        }
      } finally {
        try cleanup(afterStop)
        finally if (interrupted) Thread.currentThread().interrupt()
      }
    }
  }
}
