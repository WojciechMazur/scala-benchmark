package microbench.examples

import microbench.*
import org.openjdk.jmh.runner.IterationType

object BulkTrace:
  var warmupOnlyCalls = 0

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 1)
@Measurement(iterations = 1)
@Fork(1)
class WarmupOnlyBenchmark:
  @Setup(Level.Trial) def setup(b: BenchmarkParams): Unit = assert(b.getMeasurement().getCount() == 0)

  @Benchmark def execute(i: IterationParams): Int =
    assert(i.getType() == IterationType.WARMUP)
    BulkTrace.warmupOnlyCalls += 1
    BulkTrace.warmupOnlyCalls

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 1)
@Measurement(iterations = 1)
@Fork(1)
class BulkWarmupBenchmark:
  @Setup(Level.Trial) def setup(): Unit = assert(BulkTrace.warmupOnlyCalls > 0)

  @Benchmark def execute(i: IterationParams): Int =
    assert(BulkTrace.warmupOnlyCalls > 0); 1

@BenchmarkMode(Array(Mode.AverageTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1, time = 10, timeUnit = java.util.concurrent.TimeUnit.MILLISECONDS)
@Fork(1)
class ControlPollingBenchmark:

  @Benchmark def execute(control: Control): Long =
    var calls = 0L
    while !control.stopMeasurement do calls += 1
    calls
