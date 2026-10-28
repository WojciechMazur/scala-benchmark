package scala.scalanative.jmh.examples

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations._

@Warmup(iterations = 0)
@Measurement(iterations = 1, time = 1, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
class FailureBenchmark {
  @Benchmark def fail(): Unit = throw new IllegalStateException(
    "intentional benchmark failure"
  )
}

@Warmup(iterations = 0)
@Measurement(iterations = 1, time = 1, timeUnit = TimeUnit.MILLISECONDS)
@Timeout(time = 100, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
class TimeoutBenchmark {
  @Benchmark def block(): Unit = while (true) Thread.sleep(1000)
}

object ForkIsolationBenchmark { var trials = 0 }

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(value = 2, warmups = 1)
class ForkIsolationBenchmark {
  @Setup(Level.Trial) def setup(): Unit = {
    ForkIsolationBenchmark.trials += 1
    assert(ForkIsolationBenchmark.trials == 1)
  }
  @Benchmark def execute(): Int = ForkIsolationBenchmark.trials
}
