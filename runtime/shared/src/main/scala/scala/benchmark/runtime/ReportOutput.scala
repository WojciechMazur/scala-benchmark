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

import java.io.{ByteArrayOutputStream, OutputStream, PrintStream}

private[runtime] object ReportOutput:

  def apply[A](options: Options)(body: => A): A =
    if options.consoleOutput.isEmpty && options.verbosity != "SILENT" then
      val out = Console.out
      val err = Console.err
      val budget = options.configuration(Configuration()).timeoutNanos
      val boundedOut = Platform.consoleStream(out, 1, budget)
      val boundedErr = Platform.consoleStream(err, 2, budget)
      var primary: Throwable = null
      try Console.withOut(boundedOut)(Console.withErr(boundedErr)(body))
      catch
        case error: Throwable =>
          primary = error
          throw error
      finally
        def close(stream: PrintStream, original: PrintStream): Unit = if !(stream eq original) then
          try stream.close()
          catch
            case error: Throwable => if primary != null then primary.addSuppressed(error) else throw error
        try close(boundedOut, out)
        finally close(boundedErr, err)
    else
      val buffer = new ByteArrayOutputStream
      val silent = options.verbosity == "SILENT"
      val original = System.out
      val bounded =
        if options.consoleOutput.isEmpty then
          Some(Platform.consoleStream(original, 1, options.configuration(Configuration()).timeoutNanos))
        else None
      val destination: OutputStream = bounded.fold[OutputStream](buffer) { output =>
        new OutputStream:
          def write(value: Int): Unit = output.write(value)
          override def write(bytes: Array[Byte], offset: Int, length: Int): Unit = output.write(bytes, offset, length)
          override def flush(): Unit = output.flush()
          override def close(): Unit = if !(output eq original) then output.close()
      }
      val stream = new PrintStream(destination, true, "UTF-8"):
        override def print(text: String): Unit = if !silent then super.print(text)
        override def print(value: Any): Unit = if !silent then super.print(value)
        override def println(text: String): Unit = if !silent then super.println(text)
        override def println(value: Any): Unit = if !silent then super.println(value)
        override def println(): Unit = if !silent then super.println()
      options.consoleOutput.foreach(path => Platform.write(path, ""))
      var primary: Throwable = null
      try Console.withOut(stream)(Console.withErr(stream)(body))
      catch
        case error: Throwable =>
          primary = error
          throw error
      finally
        try
          stream.flush()
          options.consoleOutput.foreach(path => Platform.write(path, buffer.toString("UTF-8")))
        catch case error: Throwable => if primary != null then primary.addSuppressed(error) else throw error
        finally stream.close()

end ReportOutput
