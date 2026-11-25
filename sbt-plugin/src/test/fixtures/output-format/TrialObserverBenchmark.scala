package microbench.runtime.outputformat

import microbench.*

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(0)
class TrialObserverBenchmark:

  @Benchmark def execute(): Int =
    val cases = microbench.runtime.TrialObserverChecks.verify()
    assert(cases == 32)
    println("TRIAL_OBSERVER_OK:" + cases)
    cases
