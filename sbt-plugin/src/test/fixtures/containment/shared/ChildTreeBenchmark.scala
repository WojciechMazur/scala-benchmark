package scala.benchmark.examples

import scala.benchmark.*

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(1)
class ChildTreeBenchmark {
  @Param(Array("unused")) var marker: String = ""
  @Param(Array("finish")) var action: String = ""
  @Setup(Level.Trial) def setup(): Unit = TreeHost.start(marker)
  @Benchmark def execute(): Int = {
    if (action == "hang") while (true) ()
    if (action == "crash") TreeHost.crash()
    1
  }
}
