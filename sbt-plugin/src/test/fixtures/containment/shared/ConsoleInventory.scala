package scala.scalanative.jmh.examples

import scala.scalanative.jmh.*

object ConsoleInventory extends Suite {
  def metadata(): Array[String] =
    Array.tabulate(2000)(i => s"name=listing.${"x" * 256}$i\nmodes=SingleShotTime\nstates=0\nforks=0\n")
  def instantiate(id: Int): AnyRef = throw new AssertionError("Listing initialized benchmark state")
  def assign(id: Int, target: AnyRef, parameter: String, value: String): Unit = ()
  def setup(id: Int, level: Int, target: AnyRef, env: Environment): Unit = ()
  def teardown(id: Int, level: Int, target: AnyRef, env: Environment): Unit = ()
  def measure(id: Int, env: Environment, control: IterationControl): RawResult = throw new AssertionError(
    "Listing executed benchmark work"
  )
}
