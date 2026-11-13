package scala.scalanative.jmh

import java.io.StringReader
import java.util.Properties

import org.openjdk.jmh.annotations.{Mode, Scope}
import org.openjdk.jmh.runner.options.TimeValue
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.infra.{Blackhole, ThreadParams}

/** Internal compiler/runtime ABI. Not an alternative public Runner API. */
trait Suite extends PlatformSuite with scala.benchmark.internal.BenchmarkInventory {
  def metadata(): Array[String]
  def resetAuxiliary(benchmark: Int, environment: Environment): Unit = ()
  def auxiliary(benchmark: Int, environment: Environment): Array[Double] = Array.emptyDoubleArray
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
    scope: Scope,
    dependencies: Vector[Int],
    parameters: Vector[Parameter]
) {
  def shared: Boolean = scope != Scope.Thread
}
object StateDefinition {
  def apply(id: Int, shared: Boolean, dependencies: Vector[Int], parameters: Vector[Parameter]): StateDefinition =
    new StateDefinition(id, if (shared) Scope.Benchmark else Scope.Thread, dependencies, parameters)
}
final case class CounterDefinition(name: String, kind: String, member: Int)
final case class AuxiliaryValue(name: String, kind: String, value: Double)
final case class GroupMember(name: String, threads: Int, states: Vector[Int])
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
    unit: String = "SECONDS",
    synchIterations: Boolean = true,
    doGC: Boolean = false,
    threadGroups: Vector[Int] = Vector.empty,
    warmupTime: TimeValue = null,
    measurementTime: TimeValue = null,
    timeoutTime: TimeValue = null,
    parameterOrder: Map[String, Int] = Map.empty
)

object Configuration {

  /** Every resolved measurement option crosses the fork boundary, including injected metadata. */
  def time(c: Configuration, warmup: Boolean): TimeValue = {
    val nanos = if (warmup) c.warmupNanos else c.measurementNanos
    val original = if (warmup) c.warmupTime else c.measurementTime
    if (original != null && original.convertTo(TimeUnit.NANOSECONDS) == nanos) original
    else if (nanos == 0) TimeValue.NONE
    else if (nanos == 10000000000L) TimeValue.seconds(10)
    else TimeValue.nanoseconds(nanos)
  }
  def timeout(c: Configuration): TimeValue =
    if (c.timeoutTime != null && c.timeoutTime.convertTo(TimeUnit.NANOSECONDS) == c.timeoutNanos) c.timeoutTime
    else if (c.timeoutNanos == 600000000000L) TimeValue.minutes(10)
    else TimeValue.nanoseconds(c.timeoutNanos)

  def overrides(c: Configuration): Map[String, String] = Map(
    "-wi" -> c.warmupIterations.toString,
    "-i" -> c.measurementIterations.toString,
    "-w" -> s"${c.warmupNanos}ns",
    "-r" -> s"${c.measurementNanos}ns",
    "-wbs" -> c.warmupBatchSize.toString,
    "-bs" -> c.measurementBatchSize.toString,
    "-f" -> c.forks.toString,
    "-wf" -> c.warmupForks.toString,
    "-t" -> c.threads.toString,
    "-opi" -> c.operationsPerInvocation.toString,
    "-si" -> c.synchIterations.toString,
    "-gc" -> c.doGC.toString,
    "-to" -> s"${c.timeoutNanos}ns",
    "-tu" -> c.unit,
    "-w.time" -> time(c, true).getTime().toString,
    "-w.unit" -> time(c, true).getTimeUnit().name(),
    "-r.time" -> time(c, false).getTime().toString,
    "-r.unit" -> time(c, false).getTimeUnit().name(),
    "-to.time" -> timeout(c).getTime().toString,
    "-to.unit" -> timeout(c).getTimeUnit().name()
  ) ++ Option.when(c.threadGroups.nonEmpty)("-tg" -> c.threadGroups.mkString(",")).toMap ++
    c.parameterOrder.map { case (name, order) => ("-parameterOrder." + name) -> order.toString }
}

final case class WarmupRequest(
    suite: String,
    index: Int,
    mode: Mode,
    configuration: Configuration,
    parameters: Map[String, String]
)
object WarmupRequest {
  def configuration(c: Configuration): Configuration =
    c.copy(measurementIterations = 0, measurementNanos = 0, measurementBatchSize = 1, measurementTime = TimeValue.NONE)
  def execute(
      requests: Vector[WarmupRequest],
      progress: (Int, Long) => Unit = (_, _) => (),
      failOnError: Boolean = true,
      observer: RunObserver = RunObserver.Disabled
  ): Unit =
    requests.zipWithIndex.foreach { case (request, action) =>
      val c = request.configuration
      if (c.warmupIterations > 0) {
        println(s"# Bulk warmup ${request.suite} [${request.mode.shortLabel()}]")
        progress(-1, ForkProgress.budget(c.warmupNanos, c.timeoutNanos))
        val suite = Platform.loadSuite(request.suite)
        val descriptor = Descriptor.parse(request.index, suite.metadata()(request.index), Some(request.mode))
        try
          Execution.trial(
            suite,
            descriptor,
            request.mode,
            configuration(c),
            request.parameters,
            progress,
            observer = observer.warmup(action, descriptor, request.mode, configuration(c), request.parameters)
          )
        catch {
          case error: Throwable =>
            if (!ForkOutputFailure.failed)
              Console.err.println(s"# FAILED bulk warmup ${request.suite}: ${error.getMessage}")
            if (
              failOnError || error.isInstanceOf[TrialObserverFailure] || error
                .isInstanceOf[UnfinishedWorkersException] || ForkOutputFailure.failed || Platform.isCallerInterrupted
            ) throw error
        }
      }
    }
}

final case class Descriptor(
    index: Int,
    name: String,
    modes: Vector[Mode],
    configuration: Configuration,
    states: Vector[StateDefinition],
    warnings: Vector[String],
    eligibility: scala.benchmark.internal.Eligibility = scala.benchmark.internal.Eligibility(),
    generatedTarget: String = "",
    members: Vector[GroupMember] = Vector.empty,
    counters: Vector[CounterDefinition] = Vector.empty
) {
  def capture(suite: Suite, environment: Environment, raw: RawResult): RawResult = {
    if (counters.isEmpty) raw
    else {
      val definitions = counters.filter(_.member == environment.threadParams.getSubgroupIndex())
      val values = suite.auxiliary(index, environment)
      require(definitions.size == values.length, "Auxiliary counter metadata does not match the generated harness")
      raw.copy(auxiliary = definitions.zip(values).collect {
        case (counter, value) if environment.counterEnabled(counter.kind) =>
          AuxiliaryValue(counter.name, counter.kind, value)
      })
    }
  }
  def configured(c: Configuration): Descriptor = {
    if (c.threadGroups.isEmpty) this
    else {
      require(
        c.threadGroups.size == math.max(1, members.size),
        "Thread group distribution must match the benchmark member count"
      )
      require(
        c.threadGroups.forall(_ >= 0) && c.threadGroups.exists(_ > 0),
        "Thread groups must be nonnegative with at least one worker"
      )
      val current = if (members.isEmpty) Vector(GroupMember(name.split('.').last, 1, states.map(_.id))) else members
      copy(members = current.zip(c.threadGroups).map { case (member, threads) => member.copy(threads = threads) })
    }
  }
  def resolve(c: Configuration): Configuration = {
    if (members.isEmpty) c
    else {
      val size = members.map(_.threads.toLong).sum
      require(size > 0 && size <= Int.MaxValue, "Invalid group thread count")
      val threads = ((c.threads.toLong + size - 1) / size) * size
      require(threads <= Int.MaxValue, "Group thread count overflows")
      c.copy(threads = threads.toInt)
    }
  }
  def worker(index: Int, count: Int): ThreadParams = {
    if (members.isEmpty) new ThreadParams(index, count, index, count, 0, 1, 0, 1, 0, 1)
    else {
      val size = members.map(_.threads).sum
      val groupThread = index % size
      val cumulative = members.scanLeft(0)((total, member) => total + member.threads)
      val subgroup = members.indices.find(i => groupThread < cumulative(i + 1)).get
      new ThreadParams(
        index,
        count,
        index / size,
        count / size,
        subgroup,
        members.size,
        groupThread,
        size,
        groupThread - cumulative(subgroup),
        members(subgroup).threads
      )
    }
  }
  def workerStates(worker: ThreadParams): Vector[StateDefinition] = {
    if (members.isEmpty) states
    else {
      val used = members(worker.getSubgroupIndex()).states.toSet
      states.filter(s => used(s.id))
    }
  }
}

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
        Scope.valueOf(str(prefix + "scope", "Thread")),
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
        long("warmupNanos", if (single) 0L else 10000000000L),
        long("measurementNanos", if (single) 0L else 10000000000L),
        int("warmupBatchSize", 1),
        int("measurementBatchSize", 1),
        int("forks", 5),
        int("warmupForks", 0),
        int("threads", 1),
        int("operationsPerInvocation", 1),
        long("timeoutNanos", 600000000000L),
        str("unit", "SECONDS"),
        synchIterations = !single,
        warmupTime =
          if (p.containsKey("warmupTimeValue"))
            new TimeValue(long("warmupTimeValue", 0), TimeUnit.valueOf(str("warmupTimeUnit", "SECONDS")))
          else if (p.containsKey("warmupNanos")) TimeValue.nanoseconds(long("warmupNanos", 0))
          else TimeValue.seconds(if (single) 0 else 10),
        measurementTime =
          if (p.containsKey("measurementTimeValue"))
            new TimeValue(long("measurementTimeValue", 0), TimeUnit.valueOf(str("measurementTimeUnit", "SECONDS")))
          else if (p.containsKey("measurementNanos")) TimeValue.nanoseconds(long("measurementNanos", 0))
          else TimeValue.seconds(if (single) 0 else 10),
        timeoutTime =
          if (p.containsKey("timeoutTimeValue"))
            new TimeValue(long("timeoutTimeValue", 0), TimeUnit.valueOf(str("timeoutTimeUnit", "SECONDS")))
          else if (p.containsKey("timeoutNanos")) TimeValue.nanoseconds(long("timeoutNanos", 0))
          else TimeValue.minutes(10)
      ),
      states,
      Vector.tabulate(int("warnings", 0))(i => str(s"warning.$i")),
      scala.benchmark.internal.InventoryEntry.parse(text).eligibility,
      str("generatedTarget", str("name")),
      Vector.tabulate(int("members", 0)) { i =>
        GroupMember(str(s"member.$i.name"), int(s"member.$i.threads", 1), list(s"member.$i.states").map(_.toInt))
      },
      Vector.tabulate(int("counters", 0)) { i =>
        CounterDefinition(str(s"counter.$i.name"), str(s"counter.$i.kind"), int(s"counter.$i.member", 0))
      }
    )
  }

  def combinations(
      descriptor: Descriptor,
      overrides: Map[String, Vector[String]]
  ): Vector[Map[String, String]] = indexedCombinations(descriptor, overrides).map(_._1)

  def indexedCombinations(
      descriptor: Descriptor,
      overrides: Map[String, Vector[String]]
  ): Vector[(Map[String, String], Map[String, Int])] = {
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
    parameters.foldLeft(Vector(Map.empty[String, String] -> Map.empty[String, Int])) {
      case (products, (name, values)) =>
        require(values.nonEmpty, s"Parameter $name has no values")
        for ((product, orders) <- products; (value, index) <- values.zipWithIndex)
          yield product.updated(name, value) -> orders.updated(name, index)
    }
  }
}

final class Environment(
    val suite: Suite,
    val states: Array[StateSlot],
    val blackhole: Blackhole,
    val threadParams: ThreadParams,
    val benchmarkParams: org.openjdk.jmh.infra.BenchmarkParams = null,
    var iterationParams: org.openjdk.jmh.infra.IterationParams = null
) {
  var notifyControl = new org.openjdk.jmh.infra.Control
  val sampling: SamplingState = new SamplingState
  def counterEnabled(kind: String): Boolean = kind == "EVENTS" ||
    benchmarkParams.getMode() == Mode.Throughput || benchmarkParams.getMode() == Mode.AverageTime
  def state(id: Int): AnyRef = states(id).instance
  inline def guarded(id: Int, level: Int, setup: Boolean)(
      inline body: Unit
  ): Unit = {
    val slot = states(id)
    if (slot.enter(level, setup)) {
      var success = false
      try { body; success = true }
      catch { case error: Throwable => slot.fail(error); throw error }
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
      } catch { case error: Throwable => slot.fail(error); throw error }
      finally slot.leave(level, setup, success)
    }
  }
}

object Values {
  def counter(value: Any): Double = value.asInstanceOf[java.lang.Number].doubleValue()
  def zero(kind: String): AnyRef = kind match {
    case "java.lang.Byte"    => java.lang.Byte.valueOf(0.toByte)
    case "java.lang.Short"   => java.lang.Short.valueOf(0.toShort)
    case "java.lang.Integer" => java.lang.Integer.valueOf(0)
    case "java.lang.Long"    => java.lang.Long.valueOf(0L)
    case "java.lang.Float"   => java.lang.Float.valueOf(0.0f)
    case "java.lang.Double"  => java.lang.Double.valueOf(0.0)
  }
  def boolean(value: String): Boolean = {
    require(value == "true" || value == "false", s"Invalid boolean: $value")
    value == "true"
  }
  def paramBoolean(value: String): Boolean = java.lang.Boolean.parseBoolean(value)
  def byte(value: String): Byte = value.toByte
  def short(value: String): Short = value.toShort
  def char(value: String): Char = value.charAt(0)
  def int(value: String): Int = value.toInt
  def long(value: String): Long = value.toLong
  def float(value: String): Float = value.toFloat
  def double(value: String): Double = value.toDouble
  def string(value: String): String = value
}
