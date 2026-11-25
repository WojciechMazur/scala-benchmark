package microbench.examples

import microbench.*

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(0)
class ParameterOverridesBenchmark:
  @Param(Array("false")) var flag: Boolean = false
  @Param(Array("z")) var character: Char = 'z'

  @Benchmark def execute(): Int =
    assert(flag && character == 'a'); 1
