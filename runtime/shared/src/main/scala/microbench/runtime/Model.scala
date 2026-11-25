/*
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  The copyright holders
 * designate this particular file as subject to the "Classpath" exception
 * as provided in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package microbench.runtime

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.{Mode, Scope}
import org.openjdk.jmh.infra.{Blackhole, ThreadParams}
import org.openjdk.jmh.runner.options.TimeValue

/** Contract between the derived harness and the runtime. Not an alternative public Runner API. */
trait Suite:
  def metadata(): Array[BenchmarkMetadata]
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

/** Configuration annotations of a benchmark. `None` leaves the mode-dependent JMH default in place. */
final case class DeclaredConfiguration(
    warmupIterations: Option[Int] = None,
    measurementIterations: Option[Int] = None,
    warmupBatchSize: Option[Int] = None,
    measurementBatchSize: Option[Int] = None,
    warmupTime: Option[TimeValue] = None,
    measurementTime: Option[TimeValue] = None,
    timeout: Option[TimeValue] = None,
    forks: Option[Int] = None,
    warmupForks: Option[Int] = None,
    threads: Int = 1,
    operationsPerInvocation: Option[Int] = None,
    unit: String = "SECONDS"
)

/** What one benchmark, or benchmark group, declares. Derived at compile time; [[Descriptor.of]] resolves it for a
  * run.
  */
final case class BenchmarkMetadata(
    name: String,
    modes: Vector[Mode],
    states: Vector[StateDefinition] = Vector.empty,
    declared: DeclaredConfiguration = DeclaredConfiguration(),
    eligibility: microbench.internal.Eligibility = microbench.internal.Eligibility(),
    generatedTarget: String = "",
    members: Vector[GroupMember] = Vector.empty,
    counters: Vector[CounterDefinition] = Vector.empty,
    warnings: Vector[String] = Vector.empty
)

final case class Parameter(name: String, values: Vector[String])

/** The JVM that runs forked benchmarks, reported in JMH's own result fields. */
final case class JvmInfo(jvm: String, jvmArgs: Vector[String], jdkVersion: String, vmName: String, vmVersion: String)

final case class StateDefinition(
    id: Int,
    scope: Scope,
    dependencies: Vector[Int],
    parameters: Vector[Parameter]
):
  def shared: Boolean = scope != Scope.Thread

object StateDefinition:

  def apply(id: Int, shared: Boolean, dependencies: Vector[Int], parameters: Vector[Parameter]): StateDefinition =
    new StateDefinition(id, if shared then Scope.Benchmark else Scope.Thread, dependencies, parameters)

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

object Configuration:

  /** Every resolved measurement option crosses the fork boundary, including injected metadata. */
  def time(c: Configuration, warmup: Boolean): TimeValue =
    val nanos = if warmup then c.warmupNanos else c.measurementNanos
    val original = if warmup then c.warmupTime else c.measurementTime
    if original != null && original.convertTo(TimeUnit.NANOSECONDS) == nanos then original
    else if nanos == 0 then TimeValue.NONE
    else if nanos == 10000000000L then TimeValue.seconds(10)
    else TimeValue.nanoseconds(nanos)

  def timeout(c: Configuration): TimeValue =
    if c.timeoutTime != null && c.timeoutTime.convertTo(TimeUnit.NANOSECONDS) == c.timeoutNanos then c.timeoutTime
    else if c.timeoutNanos == 600000000000L then TimeValue.minutes(10)
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

end Configuration

final case class WarmupRequest(
    suite: String,
    index: Int,
    mode: Mode,
    configuration: Configuration,
    parameters: Map[String, String]
)

object WarmupRequest:

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
      if c.warmupIterations > 0 then
        println(s"# Bulk warmup ${request.suite} [${request.mode.shortLabel()}]")
        progress(-1, ForkProgress.budget(c.warmupNanos, c.timeoutNanos))
        val suite = Platform.loadSuite(request.suite)
        val descriptor = Descriptor.of(request.index, suite.metadata()(request.index), Some(request.mode))
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
        catch
          case error: Throwable =>
            if !ForkOutputFailure.failed then
              Console.err.println(s"# FAILED bulk warmup ${request.suite}: ${error.getMessage}")
            if BenchmarkRunner.mustPropagate(error, failOnError) then throw error
    }

end WarmupRequest

final case class Descriptor(
    index: Int,
    name: String,
    modes: Vector[Mode],
    configuration: Configuration,
    states: Vector[StateDefinition],
    warnings: Vector[String],
    eligibility: microbench.internal.Eligibility = microbench.internal.Eligibility(),
    generatedTarget: String = "",
    members: Vector[GroupMember] = Vector.empty,
    counters: Vector[CounterDefinition] = Vector.empty
):

  def capture(suite: Suite, environment: Environment, raw: RawResult): RawResult =
    if counters.isEmpty then raw
    else
      val definitions = counters.filter(_.member == environment.threadParams.getSubgroupIndex())
      val values = suite.auxiliary(index, environment)
      require(definitions.size == values.length, "Auxiliary counter metadata does not match the generated harness")
      raw.copy(auxiliary = definitions.zip(values).collect {
        case (counter, value) if environment.counterEnabled(counter.kind) =>
          AuxiliaryValue(counter.name, counter.kind, value)
      })

  def configured(c: Configuration): Descriptor =
    if c.threadGroups.isEmpty then this
    else
      require(
        c.threadGroups.size == math.max(1, members.size),
        "Thread group distribution must match the benchmark member count"
      )
      require(
        c.threadGroups.forall(_ >= 0) && c.threadGroups.exists(_ > 0),
        "Thread groups must be nonnegative with at least one worker"
      )
      val current = if members.isEmpty then Vector(GroupMember(name.split('.').last, 1, states.map(_.id))) else members
      copy(members = current.zip(c.threadGroups).map { case (member, threads) => member.copy(threads = threads) })

  def resolve(c: Configuration): Configuration =
    if members.isEmpty then c
    else
      val size = members.map(_.threads.toLong).sum
      require(size > 0 && size <= Int.MaxValue, "Invalid group thread count")
      val threads = ((c.threads.toLong + size - 1) / size) * size
      require(threads <= Int.MaxValue, "Group thread count overflows")
      c.copy(threads = threads.toInt)

  def worker(index: Int, count: Int): ThreadParams =
    if members.isEmpty then new ThreadParams(index, count, index, count, 0, 1, 0, 1, 0, 1)
    else
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

  def workerStates(worker: ThreadParams): Vector[StateDefinition] =
    if members.isEmpty then states
    else
      val used = members(worker.getSubgroupIndex()).states.toSet
      states.filter(s => used(s.id))

end Descriptor

object Descriptor:

  /** Resolves what a benchmark declares into the configuration of one run.
    *
    * Defaults follow JMH and depend on the mode: single-shot runs have no warmup and one unsynchronized iteration.
    *
    * @param selectedMode
    *   the mode of the run; without it, the benchmark counts as single-shot only when that is its only mode
    */
  def of(index: Int, metadata: BenchmarkMetadata, selectedMode: Option[Mode] = None): Descriptor =
    val declared = metadata.declared
    val single = selectedMode.fold(metadata.modes == Vector(Mode.SingleShotTime))(_ == Mode.SingleShotTime)
    val iterationTime = TimeValue.seconds(if single then 0 else 10)
    val warmupTime = declared.warmupTime.getOrElse(iterationTime)
    val measurementTime = declared.measurementTime.getOrElse(iterationTime)
    val timeout = declared.timeout.getOrElse(TimeValue.minutes(10))
    Descriptor(
      index,
      metadata.name,
      metadata.modes,
      Configuration(
        warmupIterations = declared.warmupIterations.getOrElse(if single then 0 else 5),
        measurementIterations = declared.measurementIterations.getOrElse(if single then 1 else 5),
        warmupNanos = warmupTime.convertTo(TimeUnit.NANOSECONDS),
        measurementNanos = measurementTime.convertTo(TimeUnit.NANOSECONDS),
        warmupBatchSize = declared.warmupBatchSize.getOrElse(1),
        measurementBatchSize = declared.measurementBatchSize.getOrElse(1),
        forks = declared.forks.getOrElse(5),
        warmupForks = declared.warmupForks.getOrElse(0),
        threads = declared.threads,
        operationsPerInvocation = declared.operationsPerInvocation.getOrElse(1),
        timeoutNanos = timeout.convertTo(TimeUnit.NANOSECONDS),
        unit = declared.unit,
        synchIterations = !single,
        warmupTime = warmupTime,
        measurementTime = measurementTime,
        timeoutTime = timeout
      ),
      metadata.states,
      metadata.warnings,
      metadata.eligibility,
      if metadata.generatedTarget.isEmpty then metadata.name else metadata.generatedTarget,
      metadata.members,
      metadata.counters
    )

  end of

  def combinations(
      descriptor: Descriptor,
      overrides: Map[String, Vector[String]]
  ): Vector[Map[String, String]] = indexedCombinations(descriptor, overrides).map(_._1)

  def indexedCombinations(
      descriptor: Descriptor,
      overrides: Map[String, Vector[String]]
  ): Vector[(Map[String, String], Map[String, Int])] =
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
        for (product, orders) <- products; (value, index) <- values.zipWithIndex
        yield product.updated(name, value) -> orders.updated(name, index)
    }

end Descriptor

final class Environment(
    val suite: Suite,
    val states: Array[StateSlot],
    val blackhole: Blackhole,
    val threadParams: ThreadParams,
    val benchmarkParams: org.openjdk.jmh.infra.BenchmarkParams = null,
    var iterationParams: org.openjdk.jmh.infra.IterationParams = null
):
  var notifyControl = new org.openjdk.jmh.infra.Control
  val sampling: SamplingState = new SamplingState

  def counterEnabled(kind: String): Boolean = kind == "EVENTS" ||
    benchmarkParams.getMode() == Mode.Throughput || benchmarkParams.getMode() == Mode.AverageTime

  def state(id: Int): AnyRef = states(id).instance

  inline def guarded(id: Int, level: Int, setup: Boolean)(
      inline body: Unit
  ): Unit =
    val slot = states(id)
    if slot.enter(level, setup) then
      var success = false
      try
        body; success = true
      catch
        case error: Throwable =>
          slot.fail(error)
          throw error
      finally slot.leave(level, setup, success)

  def fixture(id: Int, level: Int, setup: Boolean): Unit =
    guarded(id, level, setup) {
      if setup then suite.setup(id, level, state(id), this) else suite.teardown(id, level, state(id), this)
    }

end Environment

object Values:
  def counter(value: Any): Double = value.asInstanceOf[java.lang.Number].doubleValue()

  def boolean(value: String): Boolean =
    require(value == "true" || value == "false", s"Invalid boolean: $value")
    value == "true"

  def paramBoolean(value: String): Boolean = java.lang.Boolean.parseBoolean(value)
  def byte(value: String): Byte = value.toByte
  def short(value: String): Short = value.toShort
  def char(value: String): Char = value.charAt(0)
  def int(value: String): Int = value.toInt
  def long(value: String): Long = value.toLong
  def float(value: String): Float = value.toFloat
  def double(value: String): Double = value.toDouble
