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

  override def releaseFork(process: Process, token: Long): Unit = Native.releaseFork(process.pid(), token)
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
