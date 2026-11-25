package microbench.runtime.resultsextensions

import scala.jdk.CollectionConverters.*

import microbench.*
import org.openjdk.jmh.infra.BenchmarkParams
import org.openjdk.jmh.results.{
  AggregationPolicy,
  Aggregator,
  BenchmarkTaskResult,
  RawResults,
  Result,
  ResultRole,
  ScalarDerivativeResult,
  TextResult
}
import org.openjdk.jmh.util.{
  BoundedPriorityQueue,
  HashMultiset,
  HashsetMultimap,
  ListStatistics,
  Multisets,
  MultisetStatistics,
  Optional,
  SingletonStatistics,
  TreeMultiset
}

class CustomResult
    extends Result[CustomResult](ResultRole.PRIMARY, "primary", Result.of(7), "#", AggregationPolicy.AVG):

  protected def getThreadAggregator(): Aggregator[CustomResult] = new Aggregator[CustomResult]:
    def aggregate(values: java.util.Collection[CustomResult]): CustomResult = new CustomResult

  protected def getIterationAggregator(): Aggregator[CustomResult] = getThreadAggregator()

  override protected def getDerivativeResults(): java.util.Collection[? <: Result[?]] =
    java.util.Collections.singletonList(new ScalarDerivativeResult("derived", 14, "#", AggregationPolicy.AVG))

class TextAccess(text: String, name: String) extends TextResult(text, name):
  def combine(values: java.util.Collection[TextResult]): TextResult = getThreadAggregator().aggregate(values)

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(0)
class ResultExtensionBenchmark:

  @Benchmark def execute(params: BenchmarkParams): Int =
    assert(microbench.TimeValue.milliseconds(2).getTime() == 2)
    assert(microbench.VerboseMode.valueOf("SILENT") == microbench.VerboseMode.SILENT)
    assert(microbench.WarmupMode.BULK_INDI.isBulk())
    assert(microbench.ResultFormatType.valueOf("JSON") == microbench.ResultFormatType.JSON)
    assert(microbench.RunResult.DEFAULT_SORT_COMPARATOR != null)
    val singleton = new SingletonStatistics(7)
    assert(singleton.getN() == 1 && singleton.getMean() == 7 && singleton.getSum() == 7)
    assert(singleton.getVariance().isNaN && singleton.getMeanErrorAt(0.999).isNaN)
    for rank <- Vector(-1.0, 0.0, 50.0, 100.0, 101.0, Double.NaN) do assert(singleton.getPercentile(rank) == 7)
    assert(singleton.getHistogram(Array(0.0, 7.0, 8.0)).toVector == Vector(0, 1))
    assert(singleton.getHistogram(Array(0.0)).isEmpty)
    val iterator = singleton.getRawData()
    val entry = iterator.next()
    assert(entry.getKey.doubleValue() == 7 && entry.getValue.longValue() == 1 && !iterator.hasNext())
    assert(scala.util.Try(iterator.next()).failed.get.isInstanceOf[java.util.NoSuchElementException])
    assert(scala.util.Try(iterator.remove()).failed.get.isInstanceOf[UnsupportedOperationException])
    assert(scala.util.Try(entry.setValue(2L)).failed.get.isInstanceOf[UnsupportedOperationException])

    val text = new TextAccess("one", "Trace")
    val joined = text.combine(Vector[TextResult](text, new TextResult("two", "trace")).asJava)
    assert(joined.extendedInfo() == "onetwo" && joined.getLabel() == "Trace")
    assert(joined.toString() == "(text only)" && joined.getScore().isNaN)
    val one = new TextResult("a", new String(Array('s', 'a', 'm', 'e')))
    val two = new TextResult("b", new String(Array('s', 'a', 'm', 'e')))
    assert(one.getLabel() eq two.getLabel())
    assert(scala.util.Try(new TextResult("", null)).failed.get.isInstanceOf[NullPointerException])
    assert(
      scala.util.Try(text.combine(java.util.Collections.emptyList())).failed.get.isInstanceOf[NullPointerException]
    )
    assert(scala.util.Try(text.combine(Vector[TextResult](text, new TextResult("bad", "other")).asJava)).isFailure)
    val iteration = new org.openjdk.jmh.results.IterationResult(
      params,
      params.getMeasurement(),
      new org.openjdk.jmh.results.IterationResultMetaData(1, 1)
    )
    iteration.addResult(new CustomResult)
    iteration.addResult(new TextResult("a", "trace"))
    iteration.addResult(new TextResult("b", "trace"))
    assert(iteration.getPrimaryResult().getScore() == 7)
    assert(iteration.getSecondaryResults().get("derived").getScore() == 14)
    assert(iteration.getSecondaryResults().get("trace").extendedInfo() == "ab")
    val heterogeneous = new Aggregator[Result[?]]:
      def aggregate(values: java.util.Collection[Result[?]]): Result[?] = values.iterator().next()
    val original = new CustomResult
    assert(heterogeneous.aggregate(Vector[Result[?]](original, new TextResult("", "trace")).asJava) eq original)
    val task = new BenchmarkTaskResult(17, 11)
    task.add(new CustomResult)
    assert(task.getAllOps() == 17 && task.getMeasuredOps() == 11 && task.getResults().size() == 1)
    val alias = task.getResults(); alias.clear(); assert(task.getResults().isEmpty())
    val raw = new RawResults
    raw.allOps = 17.5; raw.measuredOps = 11.5; raw.startTime = 10; raw.stopTime = 30
    assert(raw.getTime() == 20 && raw.allOps == 17.5 && raw.measuredOps == 11.5)
    raw.realTime = 3; assert(raw.getTime() == 3)
    raw.realTime = -1; assert(raw.getTime() == 20)

    val set = new HashMultiset[String]
    set.add("a", 3); set.add("b", 7); set.add("c", 1)
    assert(set.size() == 11 && set.count("b") == 7)
    assert(Multisets.countHighest(set, 2).asScala.toVector == Vector("b", "a"))
    assert(Multisets.sortedDesc(set).asScala.toVector == Vector("b", "a", "c"))
    val keys = set.keys()
    set.add("a", -3); assert(!keys.contains("a") && set.size() == 8)
    assert(scala.util.Try(keys.clear()).failed.get.isInstanceOf[UnsupportedOperationException])
    val peer = new HashMultiset[String]; peer.add("b", 7); peer.add("c", 1)
    assert(peer == set && peer.hashCode() == set.hashCode())
    set.entrySet().iterator().next().setValue(99L)
    assert(set.size() == 8, "The upstream live entry view does not maintain the size cache")
    val sorted = new TreeMultiset[String]; sorted.add("z"); sorted.add("a")
    assert(sorted.keys().asScala.toVector == Vector("a", "z"))
    val multi = new HashsetMultimap[String, String]
    multi.put("a", "one"); multi.put("a", "one"); multi.put("a", "two")
    assert(multi.get("a").size() == 2)
    val queue = new BoundedPriorityQueue[java.lang.Integer](2)
    Vector(5, 1, 3, 1).foreach(i => queue.offer(i))
    assert(queue.size() == 2 && queue.poll() == 1 && queue.poll() == 1 && queue.poll() == null)
    val values = new MultisetStatistics
    values.addValue(3, 1); values.addValue(3, -1)
    assert(values.getN() == 0 && values.getSum().isNaN && values.getPercentile(Double.NaN).isNaN)
    values.addValue(3, 1); assert(values.getPercentile(Double.NaN).isNaN)
    val list = new ListStatistics(Array(1.0, 2.0, 3.0))
    assert(scala.util.Try(list.getPercentile(Double.NaN)).failed.get.isInstanceOf[ArrayIndexOutOfBoundsException])
    assert(Optional.none[String]().hashCode() == 0 && Optional.of("value").hashCode() == "value".hashCode())
    import org.openjdk.jmh.util.ScoreFormatter
    assert(ScoreFormatter.format(7) == "7.000")
    assert(ScoreFormatter.format(0) == "≈ 0")
    assert(ScoreFormatter.format(0.000001) == "≈ 10⁻⁶")
    assert(ScoreFormatter.format(12, 7) == "       7.000")
    assert(ScoreFormatter.formatError(-1) == "0.001")
    assert(ScoreFormatter.formatLatex(0.000001) == "$\\approx 10^{-6}$")
    assert(ScoreFormatter.superscript("-1234567890") == "⁻¹²³⁴⁵⁶⁷⁸⁹⁰")
    val scalar = new org.openjdk.jmh.results.ScalarResult("value", 7, "#", AggregationPolicy.AVG)
    assert(scalar.getStatistics().isInstanceOf[SingletonStatistics])
    assert(scalar.toString() == "7.000 #")
    assert(scalar.extendedInfo() == "  7.000 #" + System.lineSeparator())
    val buffer = new org.openjdk.jmh.util.SampleBuffer
    buffer.add(1); buffer.add(2); buffer.add(3)
    val sampled = new org.openjdk.jmh.results.SampleTimeResult(
      ResultRole.PRIMARY,
      "sample",
      buffer,
      java.util.concurrent.TimeUnit.NANOSECONDS
    )
    val extended = sampled.extendedInfo()
    assert(extended.contains("N = 3") && extended.contains("Histogram") && extended.contains("p(99.9999)"), extended)
    val format = new CountingFormat
    val options = new OptionsBuilder()
      .include("microbench.runtime.resultsextensions.ResultModeTarget.execute")
      .mode(Mode.All)
      .forks(0)
      .warmupIterations(0)
      .measurementIterations(1)
      .measurementTime(TimeValue.milliseconds(2))
      .verbosity(VerboseMode.SILENT)
      .build()
    val multiple = scala.util.Try(new microbench.Runner(options, format).runSingle())
    assert(multiple.failed.get.isInstanceOf[org.openjdk.jmh.runner.RunnerException])
    assert(
      format.benchmarks == 4 && format.results == 4,
      s"Mode.All runSingle lifecycle: ${format.benchmarks}/${format.results}"
    )
    val rejectedFormat = new CountingFormat
    val rejectedOptions = new OptionsBuilder()
      .include("microbench.runtime.resultsextensions.ResultMultipleModesTarget.execute")
      .mode(Mode.SingleShotTime)
      .forks(0)
      .warmupIterations(0)
      .measurementIterations(1)
      .verbosity(VerboseMode.SILENT)
      .build()
    assert(scala.util.Try(new microbench.Runner(rejectedOptions, rejectedFormat).runSingle()).isFailure)
    assert(rejectedFormat.benchmarks == 0 && rejectedFormat.results == 0)
    for forks <- Vector(0, 1) do
      val captured = new CountingFormat
      val parameterOptions = new OptionsBuilder()
        .include("microbench.runtime.resultsextensions.ResultParamTarget.execute")
        .mode(Mode.SingleShotTime)
        .forks(forks)
        .warmupIterations(0)
        .measurementIterations(1)
        .param("value", "b", "a", "b")
        .verbosity(VerboseMode.SILENT)
        .build()
      val observations = new microbench.Runner(parameterOptions, captured).run()
      assert(observations.size() == 3, "Parameter runs were collapsed: " + observations.size())
      assert(observations.asScala.toVector.map(_.getParams().getParam("value")) == Vector("b", "a", "b"))
      assert(captured.results == 3)
    println("RESULT_EXTENSIONS_OK")
    1

  end execute

end ResultExtensionBenchmark

class ResultModeTarget:
  @Benchmark def execute(): Int = 1

class CountingFormat extends org.openjdk.jmh.runner.format.OutputFormat:
  var benchmarks = 0
  var results = 0
  def startRun(): Unit = ()
  def startBenchmark(p: org.openjdk.jmh.infra.BenchmarkParams): Unit = benchmarks += 1
  def endBenchmark(r: org.openjdk.jmh.results.BenchmarkResult): Unit = ()
  def endRun(r: java.util.Collection[org.openjdk.jmh.results.RunResult]): Unit = results = r.size()
  def iteration(p: org.openjdk.jmh.infra.BenchmarkParams, i: org.openjdk.jmh.infra.IterationParams, n: Int): Unit = ()

  def iterationResult(
      p: org.openjdk.jmh.infra.BenchmarkParams,
      i: org.openjdk.jmh.infra.IterationParams,
      n: Int,
      r: org.openjdk.jmh.results.IterationResult
  ): Unit = ()

  def print(s: String): Unit = ()
  def println(s: String): Unit = ()
  def verbosePrintln(s: String): Unit = ()
  def write(value: Int): Unit = ()
  def write(value: Array[Byte]): Unit = ()
  def flush(): Unit = ()
  def close(): Unit = ()

@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
class ResultMultipleModesTarget:
  @Benchmark def execute(): Int = throw new IllegalStateException("runSingle must reject before execution")

@State(Scope.Thread)
class ResultParamTarget:
  @Param(Array("default")) var value: String = ""

  @Benchmark def execute(p: BenchmarkParams): Int =
    assert(p.getParam("value") == value)
    val parameters = new org.openjdk.jmh.runner.WorkloadParams
    parameters.put("value", value, if value == "a" then 0 else 9)
    val reference = new BenchmarkParams(
      p.getBenchmark(),
      p.generatedBenchmark(),
      p.shouldSynchIterations(),
      p.getThreads(),
      p.getThreadGroups(),
      p.getThreadGroupLabels(),
      p.getForks(),
      p.getWarmupForks(),
      p.getWarmup(),
      p.getMeasurement(),
      p.getMode(),
      parameters,
      p.getTimeUnit(),
      p.getOpsPerInvocation(),
      p.getJvm(),
      p.getJvmArgs(),
      p.getJdkVersion(),
      p.getVmName(),
      p.getVmVersion(),
      p.getJmhVersion(),
      p.getTimeout()
    )
    assert(
      if value == "a" then p.compareTo(reference) > 0 else p.compareTo(reference) < 0,
      "Worker parameter ordinal was not transported"
    )
    value.length

  end execute

end ResultParamTarget
