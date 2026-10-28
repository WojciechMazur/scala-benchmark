package scala.scalanative.jmh

import java.io.StringReader
import java.util.Properties

import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.infra.{Blackhole, ThreadParams}

/** Internal compiler/runtime ABI. Not an alternative public Runner API. */
trait Suite extends PlatformSuite {
  def metadata(): Array[String]
  def instantiate(state: Int): AnyRef
  def assign(state: Int, target: AnyRef, parameter: String, value: String): Unit
  def setup(
      state: Int,
      level: Int,
      target: AnyRef,
      environment: Environment
  ): Unit
  def teardown(
      state: Int,
      level: Int,
      target: AnyRef,
      environment: Environment
  ): Unit
  def measure(
      benchmark: Int,
      environment: Environment,
      control: IterationControl
  ): RawResult
}

final case class Parameter(name: String, values: Vector[String])
final case class StateDefinition(
    id: Int,
    shared: Boolean,
    dependencies: Vector[Int],
    parameters: Vector[Parameter]
)
final case class Configuration(
    warmupIterations: Int = 5,
    measurementIterations: Int = 5,
    warmupNanos: Long = 10000000000L,
    measurementNanos: Long = 10000000000L,
    warmupBatchSize: Int = 1,
    measurementBatchSize: Int = 1,
    forks: Int = 5,
    warmupForks: Int = 0,
    threads: Int = 1,
    operationsPerInvocation: Int = 1,
    timeoutNanos: Long = 600000000000L,
    unit: String = "SECONDS"
)

final case class Descriptor(
    index: Int,
    name: String,
    modes: Vector[Mode],
    configuration: Configuration,
    states: Vector[StateDefinition],
    warnings: Vector[String]
)

object Descriptor {
  def parse(
      index: Int,
      text: String,
      selectedMode: Option[Mode] = None
  ): Descriptor = {
    val p = new Properties
    p.load(new StringReader(text))
    def str(key: String, default: String = ""): String =
      p.getProperty(key, default)
    def int(key: String, default: Int): Int = str(key, default.toString).toInt
    def long(key: String, default: Long): Long =
      str(key, default.toString).toLong
    def list(key: String): Vector[String] =
      str(key).split(",").iterator.filter(_.nonEmpty).toVector
    val modes = list("modes").map(Mode.valueOf)
    val single = selectedMode.fold(modes == Vector(Mode.SingleShotTime))(
      _ == Mode.SingleShotTime
    )
    val ids = list("states").map(_.toInt)
    val states = ids.map { id =>
      val prefix = s"state.$id."
      val parameters = list(prefix + "params").map { name =>
        val count = int(prefix + s"param.$name.count", 0)
        Parameter(
          name,
          Vector.tabulate(count)(n => str(prefix + s"param.$name.$n"))
        )
      }
      StateDefinition(
        id,
        str(prefix + "scope") == "Benchmark",
        list(prefix + "dependencies").map(_.toInt),
        parameters
      )
    }
    Descriptor(
      index,
      str("name"),
      modes,
      Configuration(
        int("warmupIterations", if (single) 0 else 5),
        int("measurementIterations", if (single) 1 else 5),
        long("warmupNanos", 10000000000L),
        long("measurementNanos", 10000000000L),
        int("warmupBatchSize", 1),
        int("measurementBatchSize", 1),
        int("forks", 5),
        int("warmupForks", 0),
        int("threads", 1),
        int("operationsPerInvocation", 1),
        long("timeoutNanos", 600000000000L),
        str("unit", "SECONDS")
      ),
      states,
      Vector.tabulate(int("warnings", 0))(i => str(s"warning.$i"))
    )
  }

  def combinations(
      descriptor: Descriptor,
      overrides: Map[String, Vector[String]]
  ): Vector[Map[String, String]] = {
    val parameters = descriptor.states
      .flatMap(_.parameters)
      .groupBy(_.name)
      .toVector
      .sortBy(_._1)
      .map { case (name, definitions) =>
        require(
          definitions.map(_.values).distinct.size == 1,
          s"Conflicting values for parameter $name"
        )
        name -> overrides.getOrElse(name, definitions.head.values)
      }
    parameters.foldLeft(Vector(Map.empty[String, String])) { case (products, (name, values)) =>
      require(values.nonEmpty, s"Parameter $name has no values")
      for (product <- products; value <- values)
        yield product.updated(name, value)
    }
  }
}

/** State storage and fixture arbitration are deliberately separate from user state.
  */
final class StateSlot(val instance: AnyRef, val shared: Boolean) {
  private val ready = Array(false, false, false)
  private val locks =
    Array.fill(3)(new java.util.concurrent.locks.ReentrantLock)
  def enter(level: Int, setup: Boolean): Boolean = {
    if (!shared) true
    else {
      locks(level).lockInterruptibly()
      if (ready(level) != setup) true
      else { locks(level).unlock(); false }
    }
  }
  def leave(level: Int, setup: Boolean, success: Boolean): Unit = {
    if (shared) {
      if (success) ready(level) = setup
      locks(level).unlock()
    }
  }
}

final class Environment(
    val suite: Suite,
    val states: Array[StateSlot],
    val blackhole: Blackhole,
    val threadParams: ThreadParams
) {
  val sampling: SamplingState = new SamplingState
  def state(id: Int): AnyRef = states(id).instance
  inline def guarded(id: Int, level: Int, setup: Boolean)(
      inline body: Unit
  ): Unit = {
    val slot = states(id)
    if (slot.enter(level, setup)) {
      var success = false
      try { body; success = true }
      finally slot.leave(level, setup, success)
    }
  }
  def fixture(id: Int, level: Int, setup: Boolean): Unit = {
    val slot = states(id)
    if (slot.enter(level, setup)) {
      var success = false
      try {
        if (setup) suite.setup(id, level, slot.instance, this)
        else suite.teardown(id, level, slot.instance, this)
        success = true
      } finally slot.leave(level, setup, success)
    }
  }
}

object Values {
  def boolean(value: String): Boolean = {
    require(value == "true" || value == "false", s"Invalid boolean: $value")
    value == "true"
  }
  def byte(value: String): Byte = value.toByte
  def short(value: String): Short = value.toShort
  def char(value: String): Char = {
    require(value.length == 1, "Expected one character"); value.charAt(0)
  }
  def int(value: String): Int = value.toInt
  def long(value: String): Long = value.toLong
  def float(value: String): Float = value.toFloat
  def double(value: String): Double = value.toDouble
  def string(value: String): String = value
}
