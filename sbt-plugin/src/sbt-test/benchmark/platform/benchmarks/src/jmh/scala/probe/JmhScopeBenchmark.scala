package probe

import scala.benchmark.*

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(1)
class JmhScopeBenchmark:
  @Benchmark def execute(): Int = 1
