package scala.scalanative.jmh.examples

import scala.benchmark.*
import java.util.concurrent.{CountDownLatch, TimeUnit}

@State(Scope.Group)
class FailureGroupState {
  @Param(Array("none")) var boundary: String = ""
  @Param(Array("cooperative")) var behavior: String = ""
  @Param(Array("1")) var failAtIteration: Int = 1
  @volatile private var iteration = 0
  var peerEntered = new CountDownLatch(1)
  def event(stage: String, p: ThreadParams): Unit = {
    println(s"GROUP_EVENT\t${p.getGroupIndex()}\t${p.getThreadIndex()}\t$stage\tEND")
  }
  def fail(stage: String, p: ThreadParams): Unit = if (
    boundary == stage && p.getGroupIndex() == 0 && (stage == "trialSetup" || iteration >= failAtIteration)
  ) {
    event("failure:" + stage, p)
    throw new IllegalStateException("injected group failure: " + stage)
  }
  def failingInvocation: Boolean = boundary == "invoke" && iteration >= failAtIteration
  @Setup(Level.Trial) def trialSetup(p: ThreadParams): Unit = fail("trialSetup", p)
  @Setup(Level.Iteration) def iterationSetup(p: ThreadParams): Unit = {
    iteration += 1
    peerEntered = new CountDownLatch(1)
    event("iteration:" + iteration, p)
    fail("iterationSetup", p)
  }
  @Setup(Level.Invocation) def invocationSetup(p: ThreadParams): Unit = fail("invocationSetup", p)
  @TearDown(Level.Invocation) def invocationTeardown(p: ThreadParams): Unit = fail("invocationTeardown", p)
  @TearDown(Level.Iteration) def iterationTeardown(p: ThreadParams): Unit = fail("iterationTeardown", p)
  @TearDown(Level.Trial) def trialTeardown(p: ThreadParams): Unit = fail("trialTeardown", p)
}

@State(Scope.Thread)
@AuxCounters(AuxCounters.Type.EVENTS)
class FailureGroupCounters {
  private var group: FailureGroupState = null
  private var params: ThreadParams = null
  private var count = 0L
  @Setup(Level.Trial) def setup(g: FailureGroupState, p: ThreadParams): Unit = { group = g; params = p }
  def increment(): Unit = count += 1
  def events(): Long = {
    group.event("counterRead", params)
    if (params.getSubgroupIndex() == 0) group.fail("counter", params)
    count
  }
}

@State(Scope.Thread)
@AuxCounters(AuxCounters.Type.OPERATIONS)
class FailureGroupOperations {
  private var group: FailureGroupState = null
  private var params: ThreadParams = null
  private var count = 0L
  @Setup(Level.Trial) def setup(g: FailureGroupState, p: ThreadParams): Unit = { group = g; params = p }
  def increment(): Unit = count += 1
  def operations(): Long = {
    group.event("operationsRead", params)
    if (params.getSubgroupIndex() == 0) group.fail("operations", params)
    count
  }
}

@Platforms(Array(Platform.JVM, Platform.Native), reason = "Requires concurrent group workers")
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 2)
@Threads(2)
@Fork(1)
class GroupFailureBenchmark {
  @Benchmark @Group("pair") @GroupThreads(1)
  def a(
      group: FailureGroupState,
      counters: FailureGroupCounters,
      operations: FailureGroupOperations,
      p: ThreadParams
  ): Int = {
    counters.increment()
    operations.increment()
    if (group.failingInvocation && p.getGroupIndex() == 0) {
      assert(group.peerEntered.await(2, TimeUnit.SECONDS), "Peer never entered grouped work")
      group.fail("invoke", p)
    }
    1
  }
  @Benchmark @Group("pair") @GroupThreads(1)
  def b(
      group: FailureGroupState,
      counters: FailureGroupCounters,
      operations: FailureGroupOperations,
      p: ThreadParams,
      control: Control
  ): Int = {
    counters.increment()
    operations.increment()
    if (group.failingInvocation && p.getGroupIndex() == 0) {
      group.event("peerEntered", p)
      group.peerEntered.countDown()
      if (group.behavior == "stubborn") while (true) ()
      else while (!control.stopMeasurement && !Thread.currentThread().isInterrupted) ()
      group.event("peerReleased", p)
    }
    1
  }
}

@Platforms(Array(Platform.JVM, Platform.Native), reason = "Grouped failure recovery probe")
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(1)
class GroupRecoveryBenchmark {
  @Benchmark def execute(): Int = { println("GROUP_RECOVERY"); 1 }
}

@Platforms(Array(Platform.JVM, Platform.Native), reason = "Programmatic grouped failure recovery")
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(0)
class GroupApiRecoveryBenchmark {
  @Benchmark def execute(): Int = {
    import org.openjdk.jmh.runner.Runner
    import org.openjdk.jmh.runner.options.{OptionsBuilder, TimeValue, VerboseMode}
    val options = new OptionsBuilder()
      .include("Group(Failure|Recovery)Benchmark")
      .param("boundary", "invoke")
      .param("behavior", "cooperative")
      .threads(2)
      .mode(Mode.SingleShotTime)
      .warmupIterations(0)
      .measurementIterations(1)
      .forks(0)
      .timeout(TimeValue.seconds(2))
      .verbosity(VerboseMode.SILENT)
      .shouldFailOnError(false)
      .build()
    val results = new Runner(options).run()
    assert(results.size() == 1, "Failed group contaminated programmatic results")
    assert(results.iterator().next().getParams().getBenchmark().endsWith("GroupRecoveryBenchmark.execute"))
    println("GROUP_API_RECOVERY")
    results.size()
  }
}
