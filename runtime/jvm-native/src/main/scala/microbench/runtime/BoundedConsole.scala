/*
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  The copyright holders
 * designate this particular file as subject to the "Classpath" exception
 * as provided in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package microbench.runtime

import java.io.{IOException, OutputStream, PrintStream}
import java.util.concurrent.{ArrayBlockingQueue, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

/** Only primitive output bytes cross this infrastructure thread. */
final private[runtime] class BoundedConsole(destination: PrintStream, budget: Long) extends OutputStream:

  final private class Write(val bytes: Array[Byte]):
    val done = new CountDownLatch(1)
    val error = new AtomicReference[Throwable]

  private val queue = new ArrayBlockingQueue[Write](4)
  private val stop = new Write(null)

  private val worker = new Thread(
    new Runnable:
      def run(): Unit =
        var running = true
        while running do
          val entry = queue.take()
          if entry eq stop then running = false
          else
            try
              destination.write(entry.bytes)
              destination.flush()
              if destination.checkError() then throw new IOException("Console destination failed")
            catch
              case error: Throwable =>
                entry.error.set(error)
                running = false
            finally entry.done.countDown()
    ,
    "benchmark-console-output"
  )

  worker.setDaemon(true)
  worker.start()

  private def emit(bytes: Array[Byte]): Unit =
    ForkOutputFailure.check()
    val entry = new Write(bytes)
    try
      if !queue.offer(entry, budget, TimeUnit.NANOSECONDS) || !entry.done.await(budget, TimeUnit.NANOSECONDS) then
        throw WorkerContainment.quarantine(Vector(worker), ForkOutputFailure.fail("Console output timed out"))
      if entry.error.get() != null then throw ForkOutputFailure.fail("Console output failed", entry.error.get())
    catch
      case error: InterruptedException =>
        Thread.currentThread().interrupt()
        throw error

  def write(value: Int): Unit = emit(Array(value.toByte))

  override def write(bytes: Array[Byte], offset: Int, length: Int): Unit =
    var next = offset
    while next < offset + length do
      val end = math.min(offset + length, next + 8192)
      emit(java.util.Arrays.copyOfRange(bytes, next, end))
      next = end

  override def close(): Unit = if !ForkOutputFailure.failed then
    queue.offer(stop)
    var interrupted = Thread.interrupted()
    val started = System.nanoTime()
    try
      while worker.isAlive && System.nanoTime() - started < budget do
        try worker.join(10)
        catch case _: InterruptedException => interrupted = true
      if worker.isAlive then
        throw WorkerContainment.quarantine(Vector(worker), ForkOutputFailure.fail("Console output shutdown timed out"))
    finally if interrupted then Thread.currentThread().interrupt()

end BoundedConsole
