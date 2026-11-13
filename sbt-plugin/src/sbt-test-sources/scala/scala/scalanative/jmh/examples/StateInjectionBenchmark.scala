package scala.scalanative.jmh.examples

import scala.benchmark.*

@State(Scope.Thread) class InjectionThread
@State(Scope.Benchmark) class InjectionBenchmark
@State(Scope.Group) class InjectionGroup

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(0)
class StateInjectionBenchmark {
  private var thread: InjectionThread = null
  private var benchmark: InjectionBenchmark = null
  private var group: InjectionGroup = null
  @Setup def initialize(t: InjectionThread, b: InjectionBenchmark, g: InjectionGroup): Unit = {
    thread = t; benchmark = b; group = g
  }
  @Benchmark @Group("identity") def execute(
      first: InjectionThread,
      second: InjectionThread,
      b: InjectionBenchmark,
      g: InjectionGroup
  ): Int = {
    assert((first ne second) && (first ne thread) && (second ne thread))
    assert((b eq benchmark) && (g eq group))
    1
  }
  @TearDown def verify(t: InjectionThread, b: InjectionBenchmark, g: InjectionGroup): Unit = {
    assert((t ne thread) && (b eq benchmark) && (g eq group))
  }
}
