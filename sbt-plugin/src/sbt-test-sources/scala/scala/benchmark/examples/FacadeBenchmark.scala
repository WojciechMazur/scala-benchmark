package scala.benchmark.examples

import scala.benchmark.*

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(1)
class FacadeBenchmark {
  @Param(Array("7")) var size: Int = 0

  @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE) def execute(
      blackhole: Blackhole,
      thread: ThreadParams
  ): Int = {
    assert(size == 7)
    assert(thread.getThreadCount() > 0)
    blackhole.consume(size)
    size
  }
}
