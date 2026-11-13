package scala.scalanative.jmh.examples

import scala.benchmark.*

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 2)
@Fork(value = 2, warmups = 1)
class ASequenceBenchmark {
  @Param(Array("unused")) var ledger: String = ""
  @Param(Array("99")) var failFork: Int = 99
  @Param(Array("work")) var boundary: String = ""
  private var index = 0
  private def event(stage: String): Unit = {
    println(s"FORK_EVENT\t$index\t$stage\tEND")
    if (index == failFork && stage == boundary) throw new IllegalStateException(s"injected fork $index $stage")
    if (index == failFork && boundary == "crash" && stage == "work") ForkJournal.crash()
  }
  @Setup(Level.Trial) def setup(): Unit = { index = ForkJournal.next(ledger); event("setup") }
  @Benchmark def execute(): Int = { event("work"); index }
  @TearDown(Level.Trial) def teardown(): Unit = {
    event("teardown"); if (boundary == "output" && index == failFork) ForkJournal.flood()
  }
}

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(1)
@State(Scope.Thread)
class ZSequenceRecoveryBenchmark {
  @Param(Array("unused")) var ledger: String = ""
  @Param(Array("99")) var failFork: Int = 99
  @Param(Array("work")) var boundary: String = ""
  @Benchmark def execute(): Int = { println("FORK_RECOVERY"); 1 }
}

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(0)
class SequenceApiBenchmark {
  @Param(Array("unused")) var ledger: String = ""
  @Benchmark def execute(): Int = {
    import org.openjdk.jmh.runner.Runner
    import org.openjdk.jmh.runner.options.{OptionsBuilder, VerboseMode}
    val options = new OptionsBuilder()
      .include("ASequenceBenchmark")
      .include("ZSequenceRecoveryBenchmark")
      .param("ledger", ledger)
      .param("failFork", "3")
      .param("boundary", "work")
      .warmupForks(1)
      .forks(2)
      .warmupIterations(0)
      .measurementIterations(2)
      .threads(1)
      .mode(Mode.SingleShotTime)
      .verbosity(VerboseMode.SILENT)
      .shouldFailOnError(false)
      .build()
    val results = new Runner(options).run()
    assert(results.size() == 2)
    import scala.jdk.CollectionConverters.*
    val partial = results.asScala.find(_.getParams().getBenchmark().endsWith("ASequenceBenchmark.execute")).get
    assert(partial.getParams().getForks() == 2 && partial.getBenchmarkResults().size() == 1)
    assert(ForkJournal.count(ledger) == 3)
    val lateOptions = new OptionsBuilder()
      .include("ASequenceBenchmark")
      .param("ledger", ledger + ".teardown")
      .param("failFork", "3")
      .param("boundary", "teardown")
      .warmupForks(1)
      .forks(2)
      .warmupIterations(0)
      .measurementIterations(2)
      .threads(1)
      .mode(Mode.SingleShotTime)
      .verbosity(VerboseMode.SILENT)
      .shouldFailOnError(false)
      .build()
    val late = new Runner(lateOptions).run().iterator().next().getBenchmarkResults().asScala.toVector
    assert(late.map(_.getIterationResults().size()).sorted == Vector(1, 2))
    assert(
      late.find(_.getIterationResults().size() == 1).get.getMetadata() == null,
      "Failed fork manufactured completed trial metadata"
    )
    assert(late.find(_.getIterationResults().size() == 2).get.getMetadata() != null)
    println("FORK_API_RECOVERY_VERIFIED")
    results.size()
  }
}

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(1)
class A0SequenceHealthyBenchmark {
  @Benchmark def execute(): Int = 1
}
