package scala.benchmark.internal
import scala.scalanative.runtime.Intrinsics
import scala.scalanative.unsafe.*
object BlackholeSink {
  def consume(value: Long): Unit = Native.consume(value)
  def consumeReference(value: AnyRef): Unit = Native.consumeReference(Intrinsics.castObjectToRawPtr(value))
  @extern private object Native {
    @name("scalanative_jmh_consume") def consume(value: Long): Unit = extern
    @name("scalanative_jmh_consume_reference")
    def consumeReference(value: scala.scalanative.runtime.RawPtr): Unit = extern
  }
}
