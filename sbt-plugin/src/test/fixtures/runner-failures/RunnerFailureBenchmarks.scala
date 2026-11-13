package scala.scalanative.jmh.examples

import scala.benchmark.*
import org.openjdk.jmh.runner.IterationType

object WarmupFailureTrace { var attempts = 0 }

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 1)
@Measurement(iterations = 1)
@Fork(1)
class WarmupSetupFailureBenchmark {
  @Setup(Level.Trial) def trial(): Unit = { WarmupFailureTrace.attempts += 1 }
  @Setup(Level.Iteration) def setup(): Unit = throw new IllegalStateException("warmup failure: setup")
  @Benchmark def execute(): Int = { 1 }

}

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 1)
@Measurement(iterations = 1)
@Fork(1)
class WarmupWorkFailureBenchmark {
  @Setup(Level.Trial) def trial(): Unit = { WarmupFailureTrace.attempts += 1 }

  @Benchmark def execute(): Int = { throw new IllegalStateException("warmup failure: work") }

}

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 1)
@Measurement(iterations = 1)
@Fork(1)
class WarmupTeardownFailureBenchmark {
  @Setup(Level.Trial) def trial(): Unit = { WarmupFailureTrace.attempts += 1 }

  @Benchmark def execute(): Int = { 1 }
  @TearDown(Level.Iteration) def teardown(): Unit = throw new IllegalStateException("warmup failure: teardown")
}

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 1)
@Measurement(iterations = 1)
@Fork(1)
class BulkFailureMeasuredBenchmark {
  @Benchmark def execute(iteration: IterationParams): Int = {
    if (iteration.getType() == IterationType.MEASUREMENT) {
      assert(WarmupFailureTrace.attempts > 0)
      println("BULK_MEASUREMENT_OK")
    }
    1
  }
}

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 2)
@Fork(0)
class EmbeddedFailureBenchmark {
  @Param(Array("work")) var failure: String = ""
  @Param(Array("1")) var failIteration: Int = 1
  private var iteration = 0
  private def fail(stage: String): Unit = if (failure == stage && iteration >= failIteration)
    throw new IllegalStateException(s"embedded failure: $stage:$iteration")
  @Setup(Level.Iteration) def setup(): Unit = { iteration += 1; fail("setup") }
  @Benchmark def execute(): Int = { fail("work"); 1 }
  @TearDown(Level.Iteration) def teardown(): Unit = fail("teardown")
  @TearDown(Level.Trial) def trialEnd(): Unit = fail("trial")
}

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(0)
class ZEmbeddedRecoveryBenchmark {
  @Benchmark def execute(): Int = { println("EMBEDDED_RECOVERY"); 1 }
}
