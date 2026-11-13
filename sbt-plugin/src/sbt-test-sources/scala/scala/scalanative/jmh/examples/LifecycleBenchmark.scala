package scala.scalanative.jmh.examples

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.{Blackhole, ThreadParams}

@State(Scope.Benchmark)
class SharedState {
  val initialized = new AtomicInteger
  @Setup(Level.Trial) def setup(): Unit = assert(
    initialized.incrementAndGet() == 1
  )
  @TearDown(Level.Trial) def teardown(): Unit = assert(
    initialized.decrementAndGet() == 0
  )
}

@State(Scope.Thread)
class InputState {
  @Param(Array("16", "64")) private var size: Int = 0
  var input: Array[Int] = null
  @Setup(Level.Trial) def setup(shared: SharedState): Unit = {
    assert(shared.initialized.get() == 1)
    assert(size == 16 || size == 64)
    input = Array.tabulate(size)(identity)
  }
}

@State(Scope.Thread)
@Warmup(iterations = 1, time = 20, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 20, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
@Threads(2)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class LifecycleBenchmark {
  @Benchmark def sum(input: InputState, thread: ThreadParams): Long = {
    var result = thread.getThreadIndex().toLong
    var i = 0
    while (i < input.input.length) { result += input.input(i); i += 1 }
    result
  }
  @Benchmark def explicitBlackhole(
      input: InputState,
      blackhole: Blackhole
  ): Unit = blackhole.consume(input.input)
}

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 3, batchSize = 7)
@Fork(1)
class InvocationBenchmark {
  private var active = false
  private var calls = 0
  @Setup(Level.Invocation) def before(): Unit = {
    assert(!active); active = true
  }
  @TearDown(Level.Invocation) def after(): Unit = {
    assert(active); active = false
  }
  @Benchmark def invoke(): Int = { assert(active); calls += 1; calls }
  @TearDown(Level.Trial) def check(): Unit = assert(calls == 21)
}
