package scala.scalanative.jmh.examples

import scala.benchmark.*
import java.util.concurrent.TimeUnit

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1, time = 5, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
class ExecutionReadinessBenchmark {
  private var stoppedDuringSetup = false
  @Setup(Level.Iteration) def prepare(params: BenchmarkParams, control: Control): Unit = {
    if (!params.shouldSynchIterations()) {
      val start = System.nanoTime()
      while (!control.stopMeasurement && System.nanoTime() - start < 2000000000L) ()
      require(control.stopMeasurement, "Unsynchronized iteration timer did not run during setup")
      stoppedDuringSetup = true
    }
  }
  @Benchmark def execute(params: BenchmarkParams): Int = {
    assert(params.shouldSynchIterations() || stoppedDuringSetup)
    1
  }
}

object ExecutionFailureJournal {
  var failAt = ""
  val events = scala.collection.mutable.ArrayBuffer.empty[String]
  def record(stage: String): Unit = {
    events += stage
    if (stage == failAt) throw new IllegalStateException(stage)
  }
}

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(0)
class ExecutionFailureTarget {
  ExecutionFailureJournal.record("construct")
  @Setup(Level.Trial) def startTrial(): Unit = ExecutionFailureJournal.record("trialSetup")
  @Setup(Level.Iteration) def startIteration(): Unit = ExecutionFailureJournal.record("iterationSetup")
  @Setup(Level.Invocation) def startInvocation(): Unit = ExecutionFailureJournal.record("invocationSetup")
  @Benchmark def execute(): Int = { ExecutionFailureJournal.record("invoke"); 1 }
  @TearDown(Level.Invocation) def endInvocation(): Unit = ExecutionFailureJournal.record("invocationTeardown")
  @TearDown(Level.Iteration) def endIteration(): Unit = ExecutionFailureJournal.record("iterationTeardown")
  @TearDown(Level.Trial) def endTrial(): Unit = ExecutionFailureJournal.record("trialTeardown")
}

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(1)
class ExecutionFailureProbe {
  @Benchmark def execute(): Int = {
    val complete = Vector(
      "construct",
      "trialSetup",
      "iterationSetup",
      "invocationSetup",
      "invoke",
      "invocationTeardown",
      "iterationTeardown",
      "trialTeardown"
    )
    for (stage <- "" +: complete) {
      ExecutionFailureJournal.events.clear()
      ExecutionFailureJournal.failAt = stage
      var failed = false
      try
        new Runner(
          new OptionsBuilder()
            .include("scala.scalanative.jmh.examples.ExecutionFailureTarget.execute")
            .mode(Mode.SingleShotTime)
            .warmupIterations(0)
            .measurementIterations(1)
            .forks(0)
            .shouldFailOnError(true)
            .verbosity(org.openjdk.jmh.runner.options.VerboseMode.SILENT)
            .build()
        ).runSingle()
      catch {
        case error: RunnerException =>
          failed = true
          var pending = List[Throwable](error)
          var visited = Set.empty[Throwable]
          var found = false
          while (pending.nonEmpty) {
            val cause = pending.head
            pending = pending.tail
            if (!visited(cause)) {
              visited += cause
              if (Option(cause.getMessage).exists(_.contains(stage))) found = true
              pending = Option(cause.getCause).toList ::: cause.getSuppressed.toList ::: pending
            }
          }
          assert(found, "Primary lifecycle failure was lost: " + stage)
      }
      assert(failed == stage.nonEmpty, "Unexpected success/failure at " + stage)
      val expected = if (stage.isEmpty) complete else complete.take(complete.indexOf(stage) + 1)
      assert(
        ExecutionFailureJournal.events.toVector == expected,
        s"$stage: expected $expected; actual ${ExecutionFailureJournal.events.toVector}"
      )
    }
    ExecutionFailureJournal.failAt = ""
    1
  }
}
