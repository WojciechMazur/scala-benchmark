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

import scala.scalanative.unsafe.*

class ControlFields:
  val notifyControl = new org.openjdk.jmh.infra.Control

  @scala.scalanative.annotation.align(128)
  @volatile var done: Boolean = false

  @scala.scalanative.annotation.align(128)
  @volatile var failure: Throwable = scala.compiletime.uninitialized

  val ready = new java.util.concurrent.CountDownLatch(1)
  def announceReady(): Unit = ready.countDown()
  val started = new PaddedCounter
  val finished = new PaddedCounter

final class PaddedCounter:

  @scala.scalanative.annotation.align(128)
  @volatile private var value = 0

  def get(): Int = value

  // Only used at untimed iteration boundaries. A monitor avoids a new atomic
  // intrinsic while keeping the polled value in its own padded memory region.
  def incrementAndGet(): Int = synchronized { value += 1; value }

object Platform extends ProcessPlatform:
  def haltWorker(): Unit = Native.haltWorker()

  override def prepareWorker(): Unit =
    require(Native.prepareWorker() == 0, "Cannot create benchmark worker process group")

  override def processId: Long = Native.processId()

  override def attachFork(process: Process): Long =
    val token = Native.attachFork(process.pid())
    require(token != -1, "Cannot attach benchmark worker to its process containment job")
    token

  // Never signal a process group that was not established by this worker.
  override def releaseFork(process: Process, token: Long, established: => Boolean): Unit =
    if token != 0L || established then Native.releaseFork(process.pid(), token)

  def identity: scala.benchmark.Platform = scala.benchmark.Platform.Native

  /** Derived harnesses are registered. A hand-written suite object can still be named, when it enables reflective
    * instantiation itself.
    */
  def loadSuite(name: String): Suite = Registry.suite(name).getOrElse {
    scala.scalanative.reflect.Reflect
      .lookupLoadableModuleClass(name)
      .getOrElse(throw new ClassNotFoundException(name))
      .loadModule()
      .asInstanceOf[Suite]
  }

  def workerCommand(suite: String, descriptor: Descriptor): Seq[String] = Seq(executable)

  def executable: String = Zone {
    @annotation.tailrec
    def resolve(capacity: Int): String =
      val buffer = alloc[CChar](capacity)
      val result = Native.executable(buffer, capacity)
      if result == 0 then fromCString(buffer)
      else if result > capacity then resolve(result)
      else throw new IllegalStateException("Cannot resolve benchmark executable")
    resolve(4096)
  }

  def description: String = "Scala Native"

  override def buildWarnings: Seq[String] =
    Option
      .when(!scala.scalanative.meta.LinktimeInfo.releaseMode)(
        "Built in debug mode; use a release mode, such as `//> using nativeMode release-full` with scala-cli"
      )
      .toSeq

  override def validate(config: Configuration): Unit =
    require(
      config.threads == 1 || scala.scalanative.meta.LinktimeInfo.isMultithreadingEnabled,
      "Multiple benchmark threads need Scala Native multithreading; enable it or use -t 1"
    )

  /** Link-time configuration. The exact release mode, LTO and clang options are only known to the build tool. */
  override def defaultBuildInfo: Seq[(String, String)] =
    import scala.scalanative.meta.LinktimeInfo
    Seq(
      "scalaNativeVersion" -> LinktimeInfo.runtimeVersion,
      "mode" -> (if LinktimeInfo.releaseMode then "release" else "debug"),
      "gc" -> LinktimeInfo.garbageCollector,
      "multithreading" -> LinktimeInfo.isMultithreadingEnabled.toString
    )

  override def runtimeVersion: String = buildMetadata.toMap.getOrElse("scalaNativeVersion", "unknown")

  @extern private object Native:
    @name("scala_benchmark_prepare_worker") def prepareWorker(): CInt = extern
    @name("scala_benchmark_process_id") def processId(): CLongLong = extern
    @name("scala_benchmark_attach_fork") def attachFork(pid: CLongLong): CLongLong = extern
    @name("scala_benchmark_release_fork") def releaseFork(pid: CLongLong, token: CLongLong): Unit = extern

    @name("scala_benchmark_halt_worker")
    def haltWorker(): Unit = extern

    @name("scala_benchmark_executable")
    def executable(buffer: CString, length: CInt): CInt = extern

end Platform
