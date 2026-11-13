package scala.benchmark.runtime.outputformat

import scala.benchmark.*
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

import org.openjdk.jmh.infra.{BenchmarkParams, IterationParams}
import org.openjdk.jmh.results.{BenchmarkResult, IterationResult, RunResult}
import org.openjdk.jmh.runner.format.OutputFormat
import org.openjdk.jmh.runner.options.{TimeValue, VerboseMode, WarmupMode}

class Capture(val fault: String = "") extends OutputFormat:
  val trace = ArrayBuffer.empty[String]
  val iterations = ArrayBuffer.empty[IterationResult]
  val benchmarks = ArrayBuffer.empty[BenchmarkResult]
  var runs: java.util.Collection[RunResult] = null

  private def record(text: String): Unit =
    trace += text
    if text == fault then throw new IllegalStateException("callback-fault:" + text)

  def startRun(): Unit = record("startRun")

  def startBenchmark(p: BenchmarkParams): Unit = record(
    "startBenchmark:" + p.getBenchmark().split('.').dropRight(1).last
  )

  def endBenchmark(r: BenchmarkResult): Unit =
    benchmarks += r; record("endBenchmark")

  def endRun(r: java.util.Collection[RunResult]): Unit =
    runs = r; record("endRun")

  def iteration(p: BenchmarkParams, i: IterationParams, n: Int): Unit = record("iteration:" + i.getType() + ":" + n)

  def iterationResult(p: BenchmarkParams, i: IterationParams, n: Int, r: IterationResult): Unit =
    assert(r.getBenchmarkParams() == p, "callback benchmark parameters")
    assert(r.getParams() == i, "callback iteration parameters: " + r.getParams() + " / " + i)
    assert(r.getMetadata().getMeasuredOps() > 0)
    assert(r.getMetadata().getAllOps() >= r.getMetadata().getMeasuredOps())
    assert(r.getPrimaryResult().getScore().isFinite)
    iterations += r
    record("result:" + i.getType() + ":" + n)

  def print(t: String): Unit = ()
  def println(t: String): Unit = ()
  def verbosePrintln(t: String): Unit = ()
  def write(v: Int): Unit = ()
  def write(v: Array[Byte]): Unit = ()
  def flush(): Unit = record("flush")
  def close(): Unit = record("close")

end Capture

@AuxCounters(AuxCounters.Type.EVENTS)
@State(Scope.Thread)
class OutputCounters:
  var events: Long = 0L
  @Setup(Level.Iteration) def reset(): Unit = events = 0L
  @TearDown def finish(): Unit = events += 100

@State(Scope.Thread)
class OutputModeTarget:
  @Setup def setup(): Unit = Journal.worker()

  @Benchmark def execute(counters: OutputCounters): Int =
    counters.events += 1; 1

class OutputWarmupTarget:
  @Benchmark def execute(): Int = 1

class OutputWarmupFailure:
  @Benchmark def execute(): Int = throw new IllegalStateException("bulk-failure")

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(0)
class OutputFormatEdgeApiBenchmark:

  private def options(target: String, forks: Int): org.openjdk.jmh.runner.options.ChainedOptionsBuilder =
    new OptionsBuilder()
      .include("scala.benchmark.runtime.outputformat." + target + ".execute")
      .warmupIterations(1)
      .measurementIterations(2)
      .warmupBatchSize(2)
      .measurementBatchSize(3)
      .warmupTime(TimeValue.milliseconds(2))
      .measurementTime(TimeValue.milliseconds(2))
      .operationsPerInvocation(7)
      .forks(forks)
      .threads(1)
      .shouldFailOnError(false)
      .verbosity(VerboseMode.SILENT)

  @Benchmark def execute(host: BenchmarkParams): Int =
    var cases = 0
    for
      forks <- Vector(0, 1); mode <- Vector(Mode.Throughput, Mode.AverageTime, Mode.SampleTime, Mode.SingleShotTime)
    do
      println(s"OUTPUT_FORMAT_CASE:mode:$forks:$mode")
      val capture = new Capture
      val result =
        new org.openjdk.jmh.runner.Runner(options("OutputModeTarget", forks).mode(mode).build(), capture).runSingle()
      println(s"OUTPUT_FORMAT_MODE:$forks:$mode:" + capture.trace.mkString(","))
      assert(capture.iterations.size == 3, capture.trace.toString)
      assert(capture.iterations.head.getParams().getBatchSize() == 2, "warmup batch")
      assert(capture.iterations.last.getParams().getBatchSize() == 3, "measurement batch")
      assert(
        capture.iterations.last.getSecondaryResults().containsKey("events"),
        "missing events: " + capture.iterations.last.getSecondaryResults()
      )
      if mode == Mode.SingleShotTime then
        assert(
          capture.iterations.last.getSecondaryResults().get("events").getScore() == 103,
          "final events: " + capture.iterations.last.getSecondaryResults()
        )
        assert(capture.iterations.forall(_.getMetadata().getMeasuredOps() == 7), "single shot operations")
      assert(result.getAggregatedResult().getIterationResults().size() == 2)
      assert(capture.benchmarks.size == 1 && capture.benchmarks.head.getIterationResults().size() == 2)
      assert((capture.benchmarks.head.getMetadata() == null) == (forks > 0))
      assert(capture.trace.count(_ == "flush") == forks + 1)
      assert(capture.trace.count(_ == "close") == forks + 1)
      cases += 1
    for forks <- Vector(0, 1); mode <- Vector(WarmupMode.BULK, WarmupMode.BULK_INDI); warmupForks <- Vector(0, 1) do
      println(s"OUTPUT_FORMAT_CASE:bulk:$forks:$mode:$warmupForks")
      val capture = new Capture
      val result = new org.openjdk.jmh.runner.Runner(
        options("OutputModeTarget", forks)
          .mode(Mode.SingleShotTime)
          .warmupMode(mode)
          .includeWarmup("scala.benchmark.runtime.outputformat.OutputWarmupTarget.execute")
          .warmupForks(warmupForks)
          .build(),
        capture
      ).run()
      val trials = if forks == 0 then 1 else 1 + warmupForks
      val warmups = if mode == WarmupMode.BULK then 2 else 3
      assert(capture.trace.count(_.startsWith("iteration:WARMUP")) == trials * warmups, capture.trace.toString)
      assert(
        capture.trace.count(_.startsWith("startBenchmark:")) == (if forks == 0 then 3 else 1),
        capture.trace.toString
      )
      assert(
        capture.iterations.count(
          _.getParams().getType() == org.openjdk.jmh.runner.IterationType.MEASUREMENT
        ) == trials * 2
      )
      assert(result.size() == 1 && result.iterator().next().getAggregatedResult().getIterationResults().size() == 2)
      assert(capture.trace.count(_ == "close") == (if forks == 0 then 1 else trials + 1))
      cases += 1
    for forks <- Vector(0, 1); failOnError <- Vector(false, true) do
      println(s"OUTPUT_FORMAT_CASE:bulk-failure:$forks:$failOnError")
      val capture = new Capture
      val outcome = scala.util.Try(
        new org.openjdk.jmh.runner.Runner(
          options("OutputModeTarget", forks)
            .mode(Mode.SingleShotTime)
            .warmupMode(WarmupMode.BULK_INDI)
            .includeWarmup("scala.benchmark.runtime.outputformat.OutputWarmupFailure.execute")
            .shouldFailOnError(failOnError)
            .build(),
          capture
        ).run()
      )
      assert(outcome.isFailure == failOnError, capture.trace.toString)
      assert(capture.trace.contains("endRun") == !failOnError, capture.trace.toString)
      if !failOnError then assert(outcome.get.size() == 1)
      cases += 1
    for
      forks <- Vector(0, 1);
      fault <- Vector(
        "startRun",
        "startBenchmark:OutputModeTarget",
        "iteration:WARMUP:1",
        "result:WARMUP:1",
        "iteration:MEASUREMENT:1",
        "result:MEASUREMENT:2",
        "endBenchmark",
        "endRun",
        "flush",
        "close"
      )
    do
      println(s"OUTPUT_FORMAT_CASE:callback-failure:$forks:$fault")
      val capture = new Capture(fault)
      val outcome = scala.util.Try(
        new org.openjdk.jmh.runner.Runner(options("OutputModeTarget", forks).mode(Mode.SingleShotTime).build(), capture)
          .run()
      )
      // JMH can swallow iteration callback failures as benchmark/link failures. Portable callbacks are fatal infrastructure.
      if host.getVmName().startsWith("Scala") || (!fault.startsWith("iteration:") && !fault.startsWith("result:")) then
        assert(outcome.isFailure, s"forks=$forks fault=$fault trace=${capture.trace}")
        assert(capture.trace.last == fault, s"forks=$forks fault=$fault trace=${capture.trace}")
      assert(capture.trace.contains(fault), capture.trace.toString)
      println(s"OUTPUT_FORMAT_CALLBACK_FAILURE:$forks:$fault:${outcome.isFailure}:" + capture.trace.mkString(","))
      cases += 1
    println("OUTPUT_FORMAT_EDGES_OK:" + cases)
    cases

  end execute

end OutputFormatEdgeApiBenchmark

@Platforms(Array(Platform.JVM, Platform.Native))
@State(Scope.Group)
@BenchmarkMode(Array(Mode.SingleShotTime))
class OutputGroupTarget:
  @Benchmark @Group("exchange") def read(): Int = 1
  @Benchmark @Group("exchange") def write(): Int = 2

@Platforms(Array(Platform.JVM, Platform.Native))
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(0)
class OutputGroupApiBenchmark:

  @Benchmark def execute(): Int =
    for forks <- Vector(0, 1); overridden <- Vector(false, true) do
      val capture = new Capture
      val builder = new OptionsBuilder()
        .include("scala.benchmark.runtime.outputformat.OutputGroupTarget.exchange")
        .mode(Mode.SingleShotTime)
        .warmupIterations(1)
        .measurementIterations(2)
        .forks(forks)
        .threads(2)
        .operationsPerInvocation(7)
        .verbosity(VerboseMode.SILENT)
      if overridden then builder.threadGroups(1, 3).threads(5).warmupMode(WarmupMode.BULK_INDI)
      new org.openjdk.jmh.runner.Runner(builder.build(), capture).run()
      assert(capture.iterations.size == (if overridden then 4 else 3))
      capture.iterations.foreach { result =>
        assert(result.getRawPrimaryResults().size() == (if overridden then 8 else 2))
        assert(
          result.getBenchmarkParams().getThreadGroups().toVector == (if overridden then Vector(1, 3) else Vector(1, 1))
        )
        assert(result.getMetadata().getMeasuredOps() == (if overridden then 56 else 14))
        assert(result.getSecondaryResults().keySet().asScala.toSet == Set("read", "write"))
      }
    println("OUTPUT_FORMAT_GROUPS_OK")
    1

end OutputGroupApiBenchmark

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(0)
class OutputFormatListingApiBenchmark:

  @Benchmark def execute(): Int =
    val lines = ArrayBuffer.empty[String]
    val capture = new Capture:
      override def println(text: String): Unit = lines += text
    val options = new OptionsBuilder()
      .include("scala.benchmark.runtime.outputformat.OutputTarget.execute")
      .param("journal", "listed-only")
      .forks(0)
      .warmupIterations(0)
      .measurementIterations(1)
      .build()
    val runner = new scala.benchmark.Runner(options, capture)
    runner.list()
    assert(lines.exists(_.contains("OutputTarget.execute")), "listing: " + lines)
    lines.clear()
    runner.listWithParams()
    assert(lines.exists(_.contains("journal")), "parameter listing: " + lines)
    assert(capture.trace.isEmpty)
    new scala.benchmark.Runner(new OptionsBuilder().include("unmatched-output-format-benchmark").build(), capture)
      .list()
    assert(capture.trace.isEmpty)
    for single <- Vector(false, true) do
      val missing = new Capture
      val runner = new org.openjdk.jmh.runner.Runner(
        new OptionsBuilder().include("unmatched-output-format-benchmark").build(),
        missing
      )
      val result = scala.util.Try(if single then runner.runSingle() else runner.run())
      assert(result.failed.get.isInstanceOf[org.openjdk.jmh.runner.NoBenchmarksException])
      assert(
        missing.trace.toVector == (if single then Vector.empty else Vector("flush", "close")),
        missing.trace.toString
      )
    val success = new Capture
    new scala.benchmark.Runner(
      new OptionsBuilder()
        .include("scala.benchmark.runtime.outputformat.OutputModeTarget.execute")
        .mode(Mode.SingleShotTime)
        .forks(0)
        .warmupIterations(0)
        .measurementIterations(1)
        .build(),
      success
    ).runSingle()
    assert(success.trace.head == "startRun" && success.trace.last == "close")
    assert(success.iterations.size == 1)
    println("OUTPUT_FORMAT_LISTING_OK")
    1

  end execute

end OutputFormatListingApiBenchmark

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(0)
class OutputFormatCrashApiBenchmark:

  @Benchmark def execute(host: BenchmarkParams): Int =
    val capture = new Capture
    val journal = Journal.create("crash-")
    val options = new OptionsBuilder()
      .include("scala.benchmark.runtime.outputformat.OutputTarget.execute")
      .mode(Mode.SingleShotTime)
      .warmupIterations(1)
      .measurementIterations(2)
      .forks(1)
      .threads(1)
      .param("failure", "crash")
      .param("journal", journal)
      .shouldFailOnError(false)
      .verbosity(VerboseMode.SILENT)
      .build()
    val result = new org.openjdk.jmh.runner.Runner(options, capture).run()
    assert(result.isEmpty())
    assert(!capture.trace.contains("endBenchmark"))
    assert(!capture.trace.contains("result:MEASUREMENT:2"))
    if host.getVmName().startsWith("Scala") then
      assert(capture.trace.contains("result:MEASUREMENT:1"), capture.trace.toString)
      assert(capture.trace.contains("iteration:MEASUREMENT:2"), capture.trace.toString)
    assert(capture.trace.last == "close")
    println("OUTPUT_FORMAT_CRASH_OK:" + capture.trace.mkString(","))
    1

end OutputFormatCrashApiBenchmark

@Platforms(Array(Platform.Native))
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(0)
class OutputFormatCancellationApiBenchmark:
  @Benchmark def execute(): Int = CancellationChecks.verify()

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(0)
class OutputFormatMutationApiBenchmark:

  @Benchmark def execute(): Int =
    for forks <- Vector(0, 1) do
      val capture = new Capture:
        override def iterationResult(p: BenchmarkParams, i: IterationParams, n: Int, r: IterationResult): Unit =
          super.iterationResult(p, i, n, r)
          if i.getType() == org.openjdk.jmh.runner.IterationType.MEASUREMENT then
            r.addResult(
              new org.openjdk.jmh.results.ScalarResult(
                "callback",
                23,
                "#",
                org.openjdk.jmh.results.AggregationPolicy.AVG
              )
            )
      val options = new OptionsBuilder()
        .include("scala.benchmark.runtime.outputformat.OutputModeTarget.execute")
        .mode(Mode.SingleShotTime)
        .forks(forks)
        .warmupIterations(1)
        .measurementIterations(2)
        .verbosity(VerboseMode.SILENT)
        .build()
      val returned = new scala.benchmark.Runner(options, capture).run()
      assert(returned eq capture.runs, "endRun collection must be returned")
      assert(returned.iterator().next().getSecondaryResults().get("callback").getScore() == 23)
      val measured =
        capture.iterations.filter(_.getParams().getType() == org.openjdk.jmh.runner.IterationType.MEASUREMENT)
      val raw =
        returned.iterator().next().getBenchmarkResults().asScala.flatMap(_.getIterationResults().asScala).toVector
      assert(raw.size == measured.size)
      assert(raw.zip(measured).forall { case (a, b) => a eq b }, "Iteration callback objects must reach public results")
    end for
    val capture = new Capture:
      override def endRun(r: java.util.Collection[RunResult]): Unit =
        super.endRun(r); r.clear()
    val options = new OptionsBuilder()
      .include("scala.benchmark.runtime.outputformat.OutputModeTarget.execute")
      .mode(Mode.SingleShotTime)
      .forks(0)
      .warmupIterations(0)
      .measurementIterations(1)
      .verbosity(VerboseMode.SILENT)
      .build()
    assert(new scala.benchmark.Runner(options, capture).run().isEmpty())
    println("OUTPUT_FORMAT_MUTATION_OK:3")
    1

  end execute

end OutputFormatMutationApiBenchmark

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(0)
class OutputFormatReportApiBenchmark:

  @Benchmark def execute(): Int =
    var cases = 0
    for
      name <- Vector("CSV", "SCSV", "JSON", "TEXT", "LATEX", "HTML");
      format <- scala.util.Try(org.openjdk.jmh.results.format.ResultFormatType.valueOf(name)).toOption;
      forks <- Vector(0, 1);
      mode <- Vector(Mode.Throughput, Mode.AverageTime, Mode.SampleTime, Mode.SingleShotTime)
    do
      val path = Journal.create("callback-report-" + name + "-")
      val capture = new Capture:
        override def iterationResult(p: BenchmarkParams, i: IterationParams, n: Int, r: IterationResult): Unit =
          super.iterationResult(p, i, n, r)
          r.addResult(
            new org.openjdk.jmh.results.ScalarResult("callback", 23, "#", org.openjdk.jmh.results.AggregationPolicy.AVG)
          )
      val options = new OptionsBuilder()
        .include("scala.benchmark.runtime.outputformat.OutputModeTarget.execute")
        .mode(mode)
        .forks(forks)
        .warmupIterations(0)
        .measurementIterations(2)
        .measurementTime(org.openjdk.jmh.runner.options.TimeValue.milliseconds(2))
        .resultFormat(format)
        .result(path)
        .verbosity(VerboseMode.SILENT)
        .build()
      new scala.benchmark.Runner(options, capture).run()
      val text = Journal.read(path).mkString("\n")
      assert(text.contains("callback") && text.contains("23"), s"$forks/$name: $text")
      cases += 1
    val path = Journal.create("empty-report-")
    val capture = new Capture:
      override def endRun(r: java.util.Collection[RunResult]): Unit =
        super.endRun(r); r.clear()
    val options = new OptionsBuilder()
      .include("scala.benchmark.runtime.outputformat.OutputModeTarget.execute")
      .mode(Mode.SingleShotTime)
      .forks(0)
      .warmupIterations(0)
      .measurementIterations(1)
      .result(path)
      .resultFormat(org.openjdk.jmh.results.format.ResultFormatType.JSON)
      .verbosity(VerboseMode.SILENT)
      .build()
    assert(new scala.benchmark.Runner(options, capture).run().isEmpty())
    assert(Journal.read(path).mkString.replaceAll("\\s", "") == "[]")
    println("OUTPUT_FORMAT_REPORTS_OK:" + (cases + 1))
    cases + 1

  end execute

end OutputFormatReportApiBenchmark
