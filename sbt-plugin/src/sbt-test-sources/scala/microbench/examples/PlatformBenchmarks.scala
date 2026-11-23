package microbench.examples

import microbench.*

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(0)
class PlatformBenchmarks:
  @Benchmark def everywhere(): Int = 1
  @Benchmark @Platforms(Array(Platform.JVM)) def jvmOnly(): Int = 2
  @Benchmark @Platforms(Array(Platform.Native)) def nativeOnly(): Int = 3
  @Benchmark @Platforms(Array(Platform.JS)) def jsOnly(): Int = 4
  @Benchmark @Platforms(Array(Platform.Wasm)) def wasmOnly(): Int = 5

@Platforms(Array(Platform.JVM), reason = "Requires concurrent group workers")
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(0)
class JvmGroupBenchmark:
  @Benchmark @Group("workers") @GroupThreads(1) def read(): Int = 1
  @Benchmark @Group("workers") @GroupThreads(1) def write(): Int = 2

/** JMH forbids final benchmark methods. It must never see this method. */
@Platforms(Array(Platform.Native, Platform.JS, Platform.Wasm), reason = "Final method exercises non-JVM generation")
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(0)
class NonJvmBenchmark:
  @Benchmark final def execute(): Int = 1
