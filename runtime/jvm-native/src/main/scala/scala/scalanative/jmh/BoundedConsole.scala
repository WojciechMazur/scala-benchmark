package scala.scalanative.jmh

import java.io.{IOException, OutputStream, PrintStream}
import java.util.concurrent.{ArrayBlockingQueue, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

/** Only primitive output bytes cross this infrastructure thread. */
private[jmh] final class BoundedConsole(destination: PrintStream, budget: Long) extends OutputStream {
  private final class Write(val bytes: Array[Byte]) {
    val done = new CountDownLatch(1)
    val error = new AtomicReference[Throwable]
  }
  private val queue = new ArrayBlockingQueue[Write](4)
  private val stop = new Write(null)
  private val worker = new Thread(
    new Runnable {
      def run(): Unit = {
        var running = true
        while (running) {
          val entry = queue.take()
          if (entry eq stop) running = false
          else {
            try {
              destination.write(entry.bytes)
              destination.flush()
              if (destination.checkError()) throw new IOException("Console destination failed")
            } catch { case error: Throwable => entry.error.set(error); running = false }
            finally entry.done.countDown()
          }
        }
      }
    },
    "benchmark-console-output"
  )
  worker.setDaemon(true)
  worker.start()
  private def emit(bytes: Array[Byte]): Unit = {
    ForkOutputFailure.check()
    val entry = new Write(bytes)
    try {
      if (!queue.offer(entry, budget, TimeUnit.NANOSECONDS) || !entry.done.await(budget, TimeUnit.NANOSECONDS))
        throw WorkerContainment.quarantine(Vector(worker), ForkOutputFailure.fail("Console output timed out"))
      if (entry.error.get() != null) throw ForkOutputFailure.fail("Console output failed", entry.error.get())
    } catch { case error: InterruptedException => Thread.currentThread().interrupt(); throw error }
  }
  def write(value: Int): Unit = emit(Array(value.toByte))
  override def write(bytes: Array[Byte], offset: Int, length: Int): Unit = {
    var next = offset
    while (next < offset + length) {
      val end = math.min(offset + length, next + 8192)
      emit(java.util.Arrays.copyOfRange(bytes, next, end))
      next = end
    }
  }
  override def close(): Unit = if (!ForkOutputFailure.failed) {
    queue.offer(stop)
    var interrupted = Thread.interrupted()
    val started = System.nanoTime()
    try {
      while (worker.isAlive && System.nanoTime() - started < budget) {
        try worker.join(10)
        catch { case _: InterruptedException => interrupted = true }
      }
      if (worker.isAlive)
        throw WorkerContainment.quarantine(Vector(worker), ForkOutputFailure.fail("Console output shutdown timed out"))
    } finally if (interrupted) Thread.currentThread().interrupt()
  }
}
