package microbench.examples

import org.openjdk.jmh.annotations.*

/** Exercises sbt-jmh's benchmark source directory on the Native backend. */
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(1)
class JmhSourceBenchmark:
  @Benchmark def execute(): Int = 42
