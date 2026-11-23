package literal.bench {
  import microbench.*

  @BenchmarkMode(Array(Mode.SingleShotTime)) @Warmup(iterations = 0) @Measurement(iterations = 1) @Fork(0)
  class CanonicalBenchmark:
    @Benchmark def execute(): Int = 1

}

package literalXbench {
  import microbench.*

  @BenchmarkMode(Array(Mode.SingleShotTime)) @Warmup(iterations = 0) @Measurement(iterations = 1) @Fork(0)
  class CanonicalBenchmark:
    @Benchmark def execute(): Int = 2

}
