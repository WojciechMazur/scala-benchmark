package scala.benchmark.examples

import scala.benchmark.runtime.*

/** Loaded by name by the transport driver, without a derived harness. */
@scala.scalanative.reflect.annotation.EnableReflectiveInstantiation
object SerializationProbe extends Suite:

  def metadata(): Array[BenchmarkMetadata] = Array(
    BenchmarkMetadata(
      "serialization",
      Vector(org.openjdk.jmh.annotations.Mode.SingleShotTime),
      Vector(StateDefinition(0, shared = false, Vector.empty, Vector.empty)),
      DeclaredConfiguration(warmupIterations = Some(0), measurementIterations = Some(1), forks = Some(1))
    )
  )

  def instantiate(id: Int): AnyRef = new Object
  def assign(id: Int, target: AnyRef, parameter: String, value: String): Unit = ()
  def setup(id: Int, level: Int, target: AnyRef, env: Environment): Unit = ()
  def teardown(id: Int, level: Int, target: AnyRef, env: Environment): Unit = ()

  def measure(id: Int, env: Environment, control: IterationControl): RawResult =
    RawResult(1, 1, new SampleBuffer, Vector.tabulate(10000)(i => AuxiliaryValue("counter" + i, "EVENTS", i.toDouble)))
