package scala.scalanative.jmh.examples

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

@js.native @JSGlobal("__benchmarkTreeHost")
private object TreeServices extends js.Object {
  def start(path: String): Unit = js.native
  def crash(): Unit = js.native
}
object TreeHost {
  def start(path: String): Unit = TreeServices.start(path)
  def crash(): Unit = TreeServices.crash()
}
