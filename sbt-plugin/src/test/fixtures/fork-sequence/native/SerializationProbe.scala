package scala.benchmark.examples

import scala.benchmark.runtime.*

/** Loaded by name by the transport driver, without a derived harness. */
@scala.scalanative.reflect.annotation.EnableReflectiveInstantiation
object SerializationProbe extends Suite {
  def metadata(): Array[String] = Array(
    "name=serialization\nmodes=SingleShotTime\nstates=0\nwarmupIterations=0\nmeasurementIterations=1\nforks=1\n"
  )
  def instantiate(id: Int): AnyRef = new Object
  def assign(id: Int, target: AnyRef, parameter: String, value: String): Unit = ()
  def setup(id: Int, level: Int, target: AnyRef, env: Environment): Unit = ()
  def teardown(id: Int, level: Int, target: AnyRef, env: Environment): Unit = ()
  def measure(id: Int, env: Environment, control: IterationControl): RawResult =
    RawResult(1, 1, new SampleBuffer, Vector.tabulate(10000)(i => AuxiliaryValue("counter" + i, "EVENTS", i.toDouble)))
}
