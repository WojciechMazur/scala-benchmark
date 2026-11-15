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
package scala.benchmark.runtime

import java.io.PrintStream

/** Bind user consoles once outside timed loops; reporting retains its own console scope. */
private[runtime] object BenchmarkOutput:
  final case class Streams(out: PrintStream, err: PrintStream)
  private val current = new scala.util.DynamicVariable[Option[Streams]](None)
  def console: Streams = Streams(Console.out, Console.err)

  def withConsole[A](streams: Streams)(body: => A): A =
    Console.withOut(streams.out)(Console.withErr(streams.err)(body))

  def capture[A](body: => A): A = current.withValue(Some(console))(body)
  def user[A](body: => A): A = current.value.fold(body)(streams => withConsole(streams)(body))

  /** A fork owns these streams; OS startup/foreign-code output remains on the original descriptors. */
  def worker[A](out: PrintStream, err: PrintStream, reporting: Streams)(body: => A): A =
    val priorOut = System.out
    val priorErr = System.err
    System.setOut(out)
    System.setErr(err)
    try current.withValue(Some(Streams(out, err)))(withConsole(reporting)(body))
    finally
      System.setOut(priorOut)
      System.setErr(priorErr)
      try out.close()
      finally err.close()
