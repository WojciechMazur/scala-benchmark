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

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

/** The exchange directory of one fork. It holds only protocol files, so it is deleted as a whole. */
final private[runtime] class ForkDirectory(val path: Path):
  val request: Path = path.resolve("request.properties")
  val result: Path = path.resolve("result.bin")
  val progress: Path = path.resolve("deadline")
  val failurePath: Path = path.resolve("failure.properties")
  val workerReady: Path = path.resolve("worker.ready")
  val parentReady: Path = path.resolve("parent.ready")
  val userOut: Path = path.resolve("user-stdout.bin")
  val userErr: Path = path.resolve("user-stderr.bin")
  val framework: Path = path.resolve("framework.bin")
  val pendingEvent: Path = path.resolve("event.pending")

  def event(sequence: Long): Path = path.resolve(f"event-$sequence%010d")

  /** Published events, in sequence order. */
  def events(): Vector[Path] = entries().filter(_.getFileName.toString.matches("event-[0-9]{10}")).sortBy(_.toString)

  def delete(): Unit =
    entries().foreach(Files.deleteIfExists)
    Files.deleteIfExists(path)

  private def entries(): Vector[Path] =
    if !Files.isDirectory(path) then Vector.empty
    else
      val listing = Files.list(path)
      try listing.iterator().asScala.toVector
      finally listing.close()
