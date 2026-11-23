package microbench.examples

import microbench.runtime.*

object ConsoleInventory extends Suite:

  def metadata(): Array[BenchmarkMetadata] =
    Array.tabulate(2000) { i =>
      BenchmarkMetadata(
        s"listing.${"x" * 256}$i",
        Vector(org.openjdk.jmh.annotations.Mode.SingleShotTime),
        Vector(StateDefinition(0, shared = false, Vector.empty, Vector.empty)),
        DeclaredConfiguration(forks = Some(0))
      )
    }

  def instantiate(id: Int): AnyRef = throw new AssertionError("Listing initialized benchmark state")
  def assign(id: Int, target: AnyRef, parameter: String, value: String): Unit = ()
  def setup(id: Int, level: Int, target: AnyRef, env: Environment): Unit = ()
  def teardown(id: Int, level: Int, target: AnyRef, env: Environment): Unit = ()

  def measure(id: Int, env: Environment, control: IterationControl): RawResult = throw new AssertionError(
    "Listing executed benchmark work"
  )
