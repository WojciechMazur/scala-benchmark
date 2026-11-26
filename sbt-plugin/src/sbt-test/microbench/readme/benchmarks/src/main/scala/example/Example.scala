package example

import microbench.*

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
class Example:
  @Param(Array("8", "64")) var size: Int = 0

  @Benchmark def run(blackhole: Blackhole): Int =
    blackhole.consume(size)
    size
