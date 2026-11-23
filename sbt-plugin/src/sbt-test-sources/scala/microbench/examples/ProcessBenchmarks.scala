package microbench.examples

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.*

@Warmup(iterations = 0)
@Measurement(iterations = 1, time = 1, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
class FailureBenchmark:

  @Benchmark def fail(): Unit = throw new IllegalStateException(
    "intentional benchmark failure"
  )

@Warmup(iterations = 0)
@Measurement(iterations = 1, time = 1, timeUnit = TimeUnit.MILLISECONDS)
@Timeout(time = 100, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
class TimeoutBenchmark:
  @Benchmark def block(): Unit = while true do ()

object ForkIsolationBenchmark:
  var trials = 0

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(value = 2, warmups = 1)
class ForkIsolationBenchmark:

  @Setup(Level.Trial) def setup(): Unit =
    ForkIsolationBenchmark.trials += 1
    assert(ForkIsolationBenchmark.trials == 1)

  @Benchmark def execute(): Int = ForkIsolationBenchmark.trials

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(0)
class PostFailureBenchmark:
  @Benchmark def execute(): Int = 1

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(1)
class FailureRecoveryApiBenchmark:

  @Benchmark def execute(): Int =
    import org.openjdk.jmh.runner.Runner
    import org.openjdk.jmh.runner.options.{OptionsBuilder, VerboseMode}
    def options(fail: Boolean, success: Boolean) =
      val builder = new OptionsBuilder()
        .include("microbench.examples.FailureBenchmark.fail")
        .warmupIterations(0)
        .measurementIterations(1)
        .forks(0)
        .threads(1)
        .mode(Mode.SingleShotTime)
        .verbosity(VerboseMode.SILENT)
        .shouldFailOnError(fail)
      if success then builder.include("microbench.examples.PostFailureBenchmark.execute")
      builder.build()
    assert(new Runner(options(false, false)).run().isEmpty(), "Failed-only run manufactured a result")
    val recovered = new Runner(options(false, true)).run()
    assert(
      recovered
        .size() == 1 && recovered.iterator().next().getParams().getBenchmark().endsWith("PostFailureBenchmark.execute")
    )
    var failed = false
    try new Runner(options(true, true)).run()
    catch case _: org.openjdk.jmh.runner.RunnerException => failed = true
    assert(failed, "Stop-on-error request was ignored")
    println("FAILURE_RECOVERY_API_VERIFIED")
    recovered.size()

end FailureRecoveryApiBenchmark
