package scala.scalanative.jmh

import scala.scalanative.runtime.Intrinsics
import scala.scalanative.unsafe._

@scala.scalanative.reflect.annotation.EnableReflectiveInstantiation
trait PlatformSuite

class ControlFields {
  @scala.scalanative.annotation.align(128) @volatile var done: Boolean = false
  @scala.scalanative.annotation.align(128) @volatile var failure: Throwable =
    null
  val started = new PaddedCounter
  val finished = new PaddedCounter
}
final class PaddedCounter {
  @scala.scalanative.annotation.align(128) @volatile private var value = 0
  def get(): Int = value
  // Only used at untimed iteration boundaries. A monitor avoids a new atomic
  // intrinsic while keeping the polled value in its own padded memory region.
  def incrementAndGet(): Int = synchronized { value += 1; value }
}

object Platform {
  def loadSuite(name: String): Suite = scala.scalanative.reflect.Reflect
    .lookupLoadableModuleClass(name)
    .getOrElse(throw new ClassNotFoundException(name))
    .loadModule()
    .asInstanceOf[Suite]
  def consume(value: Long): Unit = Native.consume(value)
  def consumeReference(value: AnyRef): Unit =
    Native.consumeReference(Intrinsics.castObjectToRawPtr(value))
  def executable: String = Zone.acquire { implicit zone =>
    val buffer = alloc[CChar](65536)
    if (Native.executable(buffer, 65536) != 0)
      throw new IllegalStateException("Cannot resolve benchmark executable")
    fromCString(buffer)
  }
  def description: String = "Scala Native"

  @extern private object Native {
    @name("scalanative_jmh_consume") def consume(value: Long): Unit = extern
    @name("scalanative_jmh_consume_reference")
    def consumeReference(value: scala.scalanative.runtime.RawPtr): Unit = extern
    @name("scalanative_jmh_executable")
    def executable(buffer: CString, length: CInt): CInt = extern
  }
}
