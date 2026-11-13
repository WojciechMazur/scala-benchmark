package scala.benchmark.examples

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

/** Immutable data shared by benchmark workers. */
@State(Scope.Benchmark)
class SharedState {
  var coefficients: Array[Int] = _
  @Setup(Level.Trial) def prepare(): Unit =
    coefficients = Array.tabulate(256)(i => (i * 31 + 17) & 0xff)
}

/** Each worker owns its input and reusable output buffer. */
@State(Scope.Thread)
class InputState {
  @Param(Array("1024", "16384")) var size: Int = 0
  var input: Array[Int] = _
  var output: Array[Int] = _

  @Setup(Level.Trial) def prepare(shared: SharedState): Unit = {
    input = Array.tabulate(size)(i => (i * 0x9e3779b9) ^ shared.coefficients(i & 0xff))
    output = new Array[Int](size)
  }
}

/** Scan and transform preallocated batches; timings are per complete batch, not per element. */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@Threads(1)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
class LifecycleBenchmark {
  @Benchmark def sum(state: InputState): Long = {
    var result = 0L
    var i = 0
    while (i < state.input.length) {
      result += state.input(i)
      i += 1
    }
    result
  }

  @Benchmark def transform(state: InputState, blackhole: Blackhole): Unit = {
    var i = 0
    while (i < state.input.length) {
      val value = state.input(i)
      state.output(i) = (value ^ (value >>> 16)) * 31
      i += 1
    }
    blackhole.consume(state.output)
  }
}
