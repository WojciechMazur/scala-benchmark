package microbench.runtime.outputfactory

import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import microbench.*
import org.openjdk.jmh.results.{AggregationPolicy, Result as Metric, ResultRole}
import org.openjdk.jmh.runner.{IterationType, WorkloadParams}
import org.openjdk.jmh.runner.format.OutputFormatFactory

class Capture:
  val bytes = new ByteArrayOutputStream
  var flushes = 0
  var closes = 0

  val stream = new PrintStream(bytes, false, "UTF-8"):
    override def flush(): Unit =
      flushes += 1; super.flush()
    override def close(): Unit =
      closes += 1; super.close()

  def text: String = bytes.toString("UTF-8")

  def reset(): Unit =
    bytes.reset(); flushes = 0

object Data:

  def params(mode: Mode, variant: String): BenchmarkParams =
    val work = new WorkloadParams
    if variant != "basic" then work.put("size", "quoted, λ", 0)
    val count = if variant == "basic" then 0 else 2
    val groups = if variant == "basic" then Array(1) else Array(1, 1)
    val labels =
      if variant == "basic" then java.util.Collections.emptyList[String]() else Vector("left", "right").asJava
    new BenchmarkParams(
      "offline.example.Benchmark.execute",
      "offline.generated.target",
      variant == "sync",
      if variant == "basic" then 1 else if variant == "sync" then 2 else 4,
      groups,
      labels,
      1,
      0,
      new IterationParams(IterationType.WARMUP, count, TimeValue.milliseconds(2), if count == 0 then 1 else 2),
      new IterationParams(IterationType.MEASUREMENT, count, TimeValue.milliseconds(3), if count == 0 then 1 else 4),
      mode,
      work,
      TimeUnit.NANOSECONDS,
      1,
      "supplied-invoker",
      if variant == "basic" then java.util.Collections.emptyList[String]() else Vector("-Xmx123m", "-Dfoo=bar").asJava,
      "supplied-jdk",
      "OpenJDK 64-Bit Server VM",
      "supplied-version",
      "supplied-jmh",
      if variant == "group" then TimeValue.milliseconds(3) else TimeValue.seconds(1)
    )

  end params

  def iteration(p: BenchmarkParams, warmup: Boolean): IterationResult =
    val primary: Metric[?] = p.getMode() match
      case Mode.Throughput  => new ThroughputResult(ResultRole.PRIMARY, "work", 10, 1L, TimeUnit.NANOSECONDS)
      case Mode.AverageTime => new AverageTimeResult(ResultRole.PRIMARY, "work", 1, 10L, TimeUnit.NANOSECONDS)
      case Mode.SampleTime  =>
        val buffer = new SampleBuffer
        buffer.add(10L)
        new SampleTimeResult(ResultRole.PRIMARY, "work", buffer, TimeUnit.NANOSECONDS)
      case _ => new SingleShotResult(ResultRole.PRIMARY, "work", 10L, TimeUnit.NANOSECONDS)
    val value =
      new IterationResult(p, if warmup then p.getWarmup() else p.getMeasurement(), new IterationResultMetaData(1, 1))
    value.addResult(primary)
    value.addResult(new ScalarResult("alpha", 1, "#", AggregationPolicy.AVG))
    value.addResult(new ScalarResult("zebra_long", 2, "#", AggregationPolicy.AVG))
    value

end Data

@BenchmarkMode(Array(Mode.SingleShotTime)) @Warmup(iterations = 0) @Measurement(iterations = 1) @Fork(0)
class OutputFactoryBenchmark:

  @Benchmark def execute(): Int =
    assert(AggregationPolicy.values().map(_.name()).toVector == Vector("AVG", "SUM", "MAX", "MIN"))
    assert(AggregationPolicy.values().map(_.toString).toVector == Vector("Average", "Sum", "Maximum", "Minimum"))
    assert(AggregationPolicy.MAX.ordinal() == 2 && AggregationPolicy.MIN.ordinal() == 3)
    val verbosity = Vector(VerboseMode.SILENT, VerboseMode.NORMAL, VerboseMode.EXTRA)
    var count = 0
    for
      mode <- Vector(Mode.Throughput, Mode.AverageTime, Mode.SampleTime, Mode.SingleShotTime); verbose <- verbosity
    do
      val capture = new Capture
      val format = OutputFormatFactory.createFormatInstance(capture.stream, verbose)
      val prefix = mode.shortLabel() + "-" + verbose.name().toLowerCase
      format.startRun()
      assert(capture.text.isEmpty && capture.flushes == 0)
      for variant <- Vector("basic", "group", "sync") do
        format.startBenchmark(Data.params(mode, variant))
        if verbose == VerboseMode.SILENT then assert(capture.text.isEmpty)
        else assert(capture.text.contains("# Benchmark: offline.example.Benchmark.execute"))
        Transcripts.write(prefix + "-" + variant + "-header", capture.text)
        capture.reset()
      val params = Data.params(mode, "group")
      for warmup <- Vector(true, false) do
        val ip = if warmup then params.getWarmup() else params.getMeasurement()
        format.iteration(params, ip, 42)
        format.iterationResult(params, ip, 42, Data.iteration(params, warmup))
        if verbose == VerboseMode.SILENT then assert(capture.text.isEmpty && capture.flushes == 0)
        else
          assert(capture.flushes == 2)
          assert(capture.text.contains(if warmup then "# Warmup Iteration  42:" else "Iteration  42:"))
          assert(capture.text.contains("zebra_long") == !warmup)
        Transcripts.write(prefix + (if warmup then "-warmup" else "-measurement"), capture.text)
        capture.reset()
      format.endBenchmark(null)
      assert(capture.text == (if verbose == VerboseMode.SILENT then "" else System.lineSeparator()))
      capture.reset()
      val benchmark = new BenchmarkResult(params, java.util.Collections.singletonList(Data.iteration(params, false)))
      val run = new RunResult(params, java.util.Collections.singletonList(benchmark))
      format.endBenchmark(benchmark)
      Transcripts.write(prefix + "-extended", capture.text)
      capture.reset()
      if mode == Mode.SingleShotTime then
        val two = new BenchmarkResult(params, Vector.fill(2)(Data.iteration(params, false)).asJava)
        assert(two.getPrimaryResult().extendedInfo().isEmpty)
      val aggregate = new BenchmarkResult(params, Vector.fill(3)(Data.iteration(params, false)).asJava)
      format.endBenchmark(aggregate)
      Transcripts.write(prefix + "-aggregate-extended", capture.text)
      capture.reset()
      format.endRun(java.util.Collections.singletonList(run))
      if verbose == VerboseMode.SILENT then assert(capture.text.isEmpty)
      else assert(capture.text.contains("execute") && capture.text.contains("zebra_long"))
      Transcripts.write(prefix + "-summary", capture.text)
      format.close()
      assert(capture.closes == 0)
      capture.reset()
      capture.stream.print("still-open")
      assert(capture.text == "still-open")
      count += 1
    end for
    for verbose <- verbosity do
      val capture = new Capture
      val format = OutputFormatFactory.createFormatInstance(capture.stream, verbose)
      format.write('X'.toInt)
      format.write(Array('Y'.toByte, 'Z'.toByte))
      format.print("P")
      format.println("L")
      format.verbosePrintln("E")
      format.flush()
      format.close()
      assert(capture.flushes == 1 && capture.closes == 0)
      capture.stream.print("tail")
      val expected = "XYZ" + (if verbose == VerboseMode.SILENT then "" else "PL" + System.lineSeparator()) +
        (if verbose == VerboseMode.EXTRA then "E" + System.lineSeparator() else "") + "tail"
      assert(capture.text == expected, capture.text)
      Transcripts.write("raw-" + verbose.name().toLowerCase, capture.text)
      assert(scala.util.Try(format.write(null: Array[Byte])).failed.get.isInstanceOf[NullPointerException])
      val nullOut = OutputFormatFactory.createFormatInstance(null, verbose)
      nullOut.startRun()
      nullOut.close()
      assert(scala.util.Try(nullOut.flush()).failed.get.isInstanceOf[NullPointerException])
      assert(scala.util.Try(nullOut.write(1)).failed.get.isInstanceOf[NullPointerException])
      if verbose == VerboseMode.SILENT then
        nullOut.startBenchmark(null); nullOut.endBenchmark(null); nullOut.endRun(null)
        nullOut.iteration(null, null, 1); nullOut.iterationResult(null, null, 1, null)
        nullOut.print(null); nullOut.println(null); nullOut.verbosePrintln(null)
      else
        assert(scala.util.Try(format.startBenchmark(null)).failed.get.isInstanceOf[NullPointerException])
        assert(scala.util.Try(format.iteration(null, null, 1)).failed.get.isInstanceOf[NullPointerException])
        assert(
          scala.util.Try(format.iterationResult(null, null, 1, null)).failed.get.isInstanceOf[NullPointerException]
        )
      val failure = new IllegalStateException("flush-failure")
      val broken = new PrintStream(new ByteArrayOutputStream):
        override def flush(): Unit = throw failure
      assert(scala.util.Try(OutputFormatFactory.createFormatInstance(broken, verbose).flush()).failed.get eq failure)
      OutputFormatFactory.createFormatInstance(broken, verbose).close()
    end for
    assert(
      scala.util
        .Try(OutputFormatFactory.createFormatInstance(new Capture().stream, null))
        .failed
        .get
        .isInstanceOf[NullPointerException]
    )
    val facade = microbench.OutputFormatFactory.createFormatInstance(new Capture().stream, VerboseMode.SILENT)
    facade.close()
    new org.openjdk.jmh.runner.format.OutputFormatFactory
    println("OUTPUT_FACTORY_DIRECT_OK:" + count)
    count

  end execute

end OutputFactoryBenchmark

@BenchmarkMode(Array(Mode.SingleShotTime)) @Warmup(iterations = 0) @Measurement(iterations = 1) @Fork(0)
class OutputFactoryRunnerBenchmark:

  @Benchmark def execute(): Int =
    val name = "microbench.runtime.outputfactory.OutputFactoryTarget.execute"
    var count = 0
    for verbose <- Vector(VerboseMode.SILENT, VerboseMode.NORMAL, VerboseMode.EXTRA); forks <- Vector(0, 1) do
      val capture = new Capture
      val format = OutputFormatFactory.createFormatInstance(capture.stream, verbose)
      val options = new OptionsBuilder()
        .include(name)
        .mode(Mode.SingleShotTime)
        .warmupIterations(0)
        .measurementIterations(1)
        .forks(forks)
        .shouldFailOnError(true)
        .verbosity(VerboseMode.NORMAL)
        .build()
      val runner = new Runner(options, format)
      val results = runner.run()
      assert(results.size() == 1)
      assert(results.iterator().next().getPrimaryResult().getSampleCount() == 1)
      assert(capture.closes == 0)
      if verbose == VerboseMode.SILENT then
        assert(!capture.text.contains("# Benchmark:") && !capture.text.contains("Iteration   1:"), capture.text)
      else assert(capture.text.contains(name) && capture.text.contains("Iteration   1:"), capture.text)
      Transcripts.write("runner-" + verbose.name().toLowerCase + "-" + forks, capture.text)
      capture.reset()
      runner.list()
      if verbose == VerboseMode.SILENT then assert(capture.text.isEmpty) else assert(capture.text.contains(name))
      Transcripts.write("listing-" + verbose.name().toLowerCase + "-" + forks, capture.text)
      assert(capture.closes == 0)
      capture.stream.print("still-open")
      assert(capture.text.endsWith("still-open"))
      count += 1
    println("OUTPUT_FACTORY_RUNNER_OK:" + count)
    count

  end execute

end OutputFactoryRunnerBenchmark

@BenchmarkMode(Array(Mode.SingleShotTime)) @Warmup(iterations = 0) @Measurement(iterations = 1) @Fork(0)
class OutputFactoryTarget:
  @Benchmark def execute(): Long = 42L
