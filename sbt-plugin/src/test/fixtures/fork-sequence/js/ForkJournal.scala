package microbench.examples

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

@js.native @JSGlobal("__forkSequenceJournal")
private object JournalHost extends js.Object:
  def count(path: String): Int = js.native
  def next(path: String): Int = js.native
  def flood(): Unit = js.native
  def crash(): Unit = js.native

object ForkJournal:
  def count(path: String): Int = JournalHost.count(path)
  def next(path: String): Int = JournalHost.next(path)
  def flood(): Unit = JournalHost.flood()
  def crash(): Unit = JournalHost.crash()
