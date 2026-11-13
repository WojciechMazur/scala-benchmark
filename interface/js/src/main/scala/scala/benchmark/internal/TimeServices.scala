package scala.benchmark.internal

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

@js.native @JSGlobal("__scalaBenchmarkHost")
private object TimeHost extends js.Object {
  def sleep(milliseconds: Double): Unit = js.native
}
object TimeServices {
  def sleep(time: Long, unit: java.util.concurrent.TimeUnit): Unit =
    if (time > 0) TimeHost.sleep(unit.toNanos(time).toDouble / 1000000.0)
}
