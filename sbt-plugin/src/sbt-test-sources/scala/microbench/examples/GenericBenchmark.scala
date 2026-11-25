package microbench.examples

import microbench.*

@State(Scope.Thread)
class GenericInput:
  var value = 0

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(0)
class GenericBenchmark:
  private var fixtureInput: GenericInput = null

  @Setup def initialize[T <: GenericInput](input: T): Unit =
    fixtureInput = input; input.value = 42

  @Benchmark def execute[T <: GenericInput](input: T): Int =
    assert(input.value == 0 && fixtureInput.value == 42 && (input ne fixtureInput))
    input.value

  @Benchmark def constant[T](): Int = 1
