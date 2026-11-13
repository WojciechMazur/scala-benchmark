package scala.benchmark.internal

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

/** An opaque host call makes consumption observable outside linked Scala code. Its cost is part of a benchmark that
  * consumes a value, on JS and Wasm alike.
  */
object BlackholeSink {
  def consume(value: Long): Unit = Sink.consume(value.asInstanceOf[js.Any])
  def consumeReference(value: AnyRef): Unit = Sink.consume(value.asInstanceOf[js.Any])

  @js.native @JSGlobal("__scalaBenchmarkHost")
  private object Sink extends js.Object {
    def consume(value: js.Any): Unit = js.native
  }
}
