package scala.benchmark.examples

import scala.benchmark.*
import org.openjdk.jmh.runner.IterationType

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 1, batchSize = 3)
@Measurement(iterations = 2, batchSize = 5)
@OperationsPerInvocation(7)
@Fork(1)
class InfrastructureBenchmark {
  @Param(Array("13")) var expectedOpi: Int = 0
  private var phases = 0
  private var calls = 0
  private var benchmark: BenchmarkParams = null
  private var iteration: IterationParams = null

  @Setup(Level.Trial) def beforeTrial(b: BenchmarkParams): Unit = {
    benchmark = b
    assert(b.getBenchmark().endsWith("InfrastructureBenchmark.execute"))
    assert(b.getThreads() == 1 && b.getOpsPerInvocation() == expectedOpi)
    assert(b.getParam("expectedOpi") == expectedOpi.toString)
    assert(!b.shouldSynchIterations())
    assert(b.getWarmup().getCount() == 1 && b.getMeasurement().getCount() == 2)
  }
  @Setup(Level.Iteration) def beforeIteration(i: IterationParams, c: Control): Unit = {
    assert(!c.startMeasurement)
    iteration = i
    phases += 1
    assert(i.getType() == (if (phases == 1) IterationType.WARMUP else IterationType.MEASUREMENT))
    assert(i.getBatchSize() == (if (phases == 1) 3 else 5))
  }
  @Benchmark def execute(b: BenchmarkParams, i: IterationParams, c: Control): Int = {
    assert(b eq benchmark)
    assert(i eq iteration)
    assert(c.startMeasurement)
    calls += 1
    calls
  }
  @TearDown(Level.Iteration) def afterIteration(c: Control): Unit = assert(c.startMeasurement)
  @TearDown(Level.Trial) def afterTrial(): Unit = assert(phases == 3 && calls == 13)
}
