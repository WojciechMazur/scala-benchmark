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

private[runtime] object WorkerContainment:
  private var processes = Vector.empty[Process]
  private var survivors = Vector.empty[Thread]

  def check(): Unit = synchronized {
    survivors = survivors.filter(_.isAlive)
    processes = processes.filter(_.isAlive)
    if processes.nonEmpty then throw new UnfinishedWorkersException(Vector.fill(processes.size)("fork process"), null)
    if survivors.nonEmpty then throw new UnfinishedWorkersException(survivors.map(_.getName), null)
  }

  def quarantine(process: Process, cause: Throwable): UnfinishedWorkersException = synchronized {
    processes = (processes :+ process).distinct.filter(_.isAlive)
    new UnfinishedWorkersException(Vector.fill(processes.size)("fork process"), cause)
  }

  def quarantine(workers: Vector[Thread], cause: Throwable): UnfinishedWorkersException = synchronized {
    survivors = (survivors ++ workers).distinct.filter(_.isAlive)
    new UnfinishedWorkersException(survivors.map(_.getName), cause)
  }
