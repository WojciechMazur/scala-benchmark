package microbench.examples

import microbench.*

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(1)
class ChildTreeBenchmark:
  @Param(Array("unused")) var marker: String = ""
  @Param(Array("finish")) var action: String = ""
  @Setup(Level.Trial) def setup(): Unit = TreeHost.start(marker)

  @Benchmark def execute(): Int =
    if action == "hang" then while true do ()
    if action == "crash" then TreeHost.crash()
    1
