package scala.scalanative.jmh.examples

import org.openjdk.jmh.annotations.*
import scala.scalanative.jmh.Platform

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(1)
class ForkContainmentBenchmark {
  @Param(Array("none")) var boundary: String = ""
  @Param(Array("fail")) var action: String = ""
  @Param(Array("unused")) var marker: String = ""

  private def visit(stage: String): Unit = if (boundary == stage) {
    Platform.write(marker, stage)
    if (action == "hang") while (true) ()
    else throw new IllegalStateException("injected fork failure: " + stage)
  }
  @Setup(Level.Trial) def trialSetup(): Unit = visit("trialSetup")
  @Setup(Level.Iteration) def iterationSetup(): Unit = visit("iterationSetup")
  @Setup(Level.Invocation) def invocationSetup(): Unit = visit("invocationSetup")
  @Benchmark def execute(): Int = { visit("invoke"); 1 }
  @TearDown(Level.Invocation) def invocationTeardown(): Unit = visit("invocationTeardown")
  @TearDown(Level.Iteration) def iterationTeardown(): Unit = visit("iterationTeardown")
  @TearDown(Level.Trial) def trialTeardown(): Unit = visit("trialTeardown")
}
