package scala.benchmark.examples

import java.util.concurrent.TimeUnit

import scala.benchmark.*
import scala.compiletime.uninitialized

/** Hash a batch of integer keys using the Scala facade API. Setup stays outside measured code. */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@Threads(1)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
class FacadeBenchmark:
  @Param(Array("128", "4096")) var size: Int = 0
  private var keys: Array[Int] = uninitialized

  @Setup(Level.Trial) def prepare(): Unit =
    keys = Array.tabulate(size)(i => (i * 0x9e3779b9) ^ (i >>> 3))

  @Benchmark def execute(blackhole: Blackhole): Unit =
    var hash = 0x811c9dc5
    var i = 0
    while i < keys.length do
      hash = (hash ^ keys(i)) * 0x01000193
      i += 1
    blackhole.consume(hash)
