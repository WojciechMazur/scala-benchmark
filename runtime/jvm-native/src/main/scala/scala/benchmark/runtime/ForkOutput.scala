/*
 * Copyright (c) 2026, Scala Benchmark contributors.
 *
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
package scala.benchmark.runtime

import java.io.{InputStream, InputStreamReader, PrintStream}
import java.util.concurrent.atomic.AtomicReference

final private[runtime] class ForkOutput(
    input: InputStream,
    destination: PrintStream,
    raw: Boolean = false,
    keepReading: () => Boolean = () => false
):
  private val error = new AtomicReference[Throwable]

  private val thread = new Thread(
    new Runnable:
      def run(): Unit =
        var reader: InputStreamReader = null
        try
          if raw then
            val buffer = new Array[Byte](8192)
            var reading = true
            while reading do
              var count = input.read(buffer)
              if count == -1 && !keepReading() then
                // The last append may race the first EOF read; re-read after observing worker termination.
                count = input.read(buffer)
                if count == -1 then reading = false
              if count == -1 && reading then Thread.sleep(5)
              else if count >= 0 then
                destination.write(buffer, 0, count)
                if destination.checkError() then throw new java.io.IOException("Benchmark output destination failed")
          else
            reader = new InputStreamReader(input, "UTF-8")
            val buffer = new Array[Char](8192)
            var count = reader.read(buffer)
            while count != -1 do
              destination.print(new String(buffer, 0, count))
              if destination.checkError() then throw new java.io.IOException("Benchmark output destination failed")
              count = reader.read(buffer)
        catch case failure: Throwable => error.set(failure)
        finally
          try if reader != null then reader.close() else input.close()
          catch
            case failure: Throwable =>
              error.compareAndSet(null, failure)
              ()
      end run
    ,
    "benchmark-fork-output"
  )

  thread.setDaemon(true)
  thread.start()

  def await(timeoutNanos: Long): Unit =
    val budget = math.max(1000000L, timeoutNanos)
    thread.join(budget / 1000000L, (budget % 1000000L).toInt)
    if thread.isAlive then
      try input.close()
      catch case _: Throwable => ()
      val cause = ForkOutputFailure.fail("Benchmark output relay timed out")
      throw WorkerContainment.quarantine(Vector(thread), cause)
    if error.get() != null then throw ForkOutputFailure.fail("Benchmark output relay failed", error.get())

end ForkOutput
