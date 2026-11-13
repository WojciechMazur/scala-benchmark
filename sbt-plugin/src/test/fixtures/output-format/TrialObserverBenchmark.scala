package scala.benchmark.runtime.outputformat

import scala.benchmark.*

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(0)
class TrialObserverBenchmark {
  @Benchmark def execute(): Int = {
    val cases = scala.benchmark.runtime.TrialObserverChecks.verify()
    assert(cases == 32)
    println("TRIAL_OBSERVER_OK:" + cases)
    cases
  }
}
