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

/** The coordinator owns the write end of worker stdin. EOF also arrives after an uncatchable coordinator death, without
  * involving benchmark threads.
  */
private[runtime] object ForkLifeline:

  def start(input: java.io.InputStream, lost: () => Unit): Thread =
    val guard = new Thread(
      new Runnable:
        def run(): Unit =
          try while input.read() != -1 do ()
          finally
            lost()
      ,
      "benchmark-fork-lifeline"
    )
    guard.setDaemon(true)
    guard.start()
    guard
