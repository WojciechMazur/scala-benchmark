package scala.benchmark.runtime

import java.util.concurrent.TimeUnit

private[runtime] object ForkProcess:

  /** Preserve the original failure and caller interruption while bounding cleanup. */
  def managed[A](process: Process)(body: => A)(afterStop: => Unit): A =
    var failure: Throwable = null
    var interrupted = false
    try body
    catch
      case error: Throwable =>
        failure = error
        interrupted = error.isInstanceOf[InterruptedException]
        throw error
    finally
      interrupted = Thread.interrupted() || interrupted
      def cleanup(action: => Unit): Unit = try action
      catch
        case error: Throwable =>
          if error.isInstanceOf[InterruptedException] then interrupted = true
          if error.isInstanceOf[UnfinishedWorkersException] then
            val fatal =
              if failure == null || (error.getCause eq failure) then error
              else if failure.isInstanceOf[UnfinishedWorkersException] then
                failure.addSuppressed(error); failure
              else
                val combined =
                  new UnfinishedWorkersException(error.asInstanceOf[UnfinishedWorkersException].workers, failure)
                combined.addSuppressed(error)
                combined
            failure = fatal
            throw fatal
          else if failure != null then failure.addSuppressed(error)
          else
            failure = error; throw error
      try
        cleanup {
          if process.isAlive then
            process.destroyForcibly()
            val started = System.nanoTime()
            var stopped = false
            while !stopped && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1) do
              try stopped = process.waitFor(25, TimeUnit.MILLISECONDS)
              catch case _: InterruptedException => interrupted = true
            if !stopped && process.isAlive then throw WorkerContainment.quarantine(process, failure)
        }
      finally
        try cleanup(afterStop)
        finally if interrupted then Thread.currentThread().interrupt()
    end try

  end managed

end ForkProcess
