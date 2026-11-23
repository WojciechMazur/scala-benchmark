package microbench.examples

import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import microbench.*
import org.openjdk.jmh.results.{RunResult as ApiRun, *}
import org.openjdk.jmh.util.{ListStatistics, MultisetStatistics, SampleBuffer}

object AggregationEdgeChecks:

  private def emit(id: String, numbers: Iterable[Double]): Unit =
    println(
      "AGGREGATION\t" + id + "\t" +
        numbers
          .map(value =>
            if value == 0.0 && java.lang.Double.doubleToRawLongBits(value) < 0 then "-0.0" else value.toString
          )
          .mkString(",")
    )

  private def metric(id: String, result: Result[?]): Unit =
    val s = result.getStatistics()
    emit(
      id,
      Vector(
        result.getScore(),
        result.getSampleCount().toDouble,
        result.getScoreError(),
        s.getMean(),
        s.getSum(),
        s.getMin(),
        s.getMax(),
        s.getVariance()
      ) ++ result.getScoreConfidence().toVector ++
        Vector(0.0, 50.0, 90.0, 99.9, 100.0).map(s.getPercentile)
    )

  private class ScalarAccess extends ScalarResult("x", 1.0, "#", AggregationPolicy.AVG):
    def join(values: Vector[ScalarResult]): Result[?] = getThreadAggregator().aggregate(values.asJava)

  private def rejected(id: String)(body: => Any): Unit =
    val outcome = try
      body; 0.0
    catch
      case _: IllegalStateException => 1.0;
      case _: Throwable             => -1.0
    emit(id, Vector(outcome))

  def apply(params: BenchmarkParams): Unit =
    for mode <- Vector("throughput", "average", "single", "sample") do
      def primary(value: Int): Result[?] = mode match
        case "throughput" =>
          new ThroughputResult(ResultRole.PRIMARY, "work", value.toDouble, 1000L + value * 7, TimeUnit.MICROSECONDS)
        case "average" =>
          new AverageTimeResult(ResultRole.PRIMARY, "work", value.toDouble, 1000L + value * 7, TimeUnit.MICROSECONDS)
        case "single" => new SingleShotResult(ResultRole.PRIMARY, "work", 1000L + value * 7, 3L, TimeUnit.MICROSECONDS)
        case "sample" =>
          val buffer = new SampleBuffer
          for _ <- 0 until value % 5 + 1 do buffer.add(value.toLong * 17)
          buffer.add(value.toLong * 31)
          new SampleTimeResult(ResultRole.PRIMARY, "work", buffer, TimeUnit.MICROSECONDS)
      def iterations(count: Int, fork: Int): Vector[IterationResult] = Vector.tabulate(count) { index =>
        val iteration = new IterationResult(params, params.getMeasurement(), new IterationResultMetaData(1, 1))
        for worker <- 0 until index % 3 + 1 do
          iteration.addResult(primary(2 + fork * 11 + index * 3 + worker))
          if index % 2 == 0 && worker == 0 then
            for
              (label, policy) <- Vector(
                "avg" -> AggregationPolicy.AVG,
                "sum" -> AggregationPolicy.SUM,
                "min" -> AggregationPolicy.MIN,
                "max" -> AggregationPolicy.MAX
              )
            do
              iteration.addResult(new ScalarResult(label, 4 + fork + index, "#", policy))
        metric(s"$mode/fork-$fork/iteration-$index", iteration.getPrimaryResult())
        iteration
      }
      val forks = Vector(1, 3, 2).zipWithIndex.map { case (count, fork) =>
        val benchmark = new BenchmarkResult(params, iterations(count, fork).asJava)
        metric(s"$mode/fork-$fork", benchmark.getPrimaryResult())
        benchmark.getSecondaryResults().asScala.foreach { case (label, value) =>
          metric(s"$mode/fork-$fork/$label", value)
        }
        benchmark
      }
      val run = new ApiRun(params, forks.asJava)
      metric(s"$mode/run", run.getPrimaryResult())
      run.getSecondaryResults().asScala.foreach { case (label, value) => metric(s"$mode/run/$label", value) }
      forks.head.addBenchmarkResult(primary(97))
      forks.head.addBenchmarkResult(new ScalarResult("avg", 31, "#", AggregationPolicy.AVG))
      metric(s"$mode/updated-fork", forks.head.getPrimaryResult())
      metric(s"$mode/updated-run", run.getPrimaryResult())
      run.getSecondaryResults().asScala.foreach { case (label, value) => metric(s"$mode/updated-run/$label", value) }
    end for
    val large = new MultisetStatistics
    large.addValue(1.25, 9007199254740987L); large.addValue(4.5, 6L)
    println("AGGREGATION\texact/large-population\t" + large.getN().toString)
    emit("statistics/large-population", Vector(large.getMean(), large.getVariance(), large.getPercentile(50)))
    val scalar = new ScalarAccess
    metric(
      "validation/reused-aggregator",
      scalar.join(
        Vector(
          new ScalarResult("other", 2, "units", AggregationPolicy.SUM),
          new ScalarResult("other", 3, "units", AggregationPolicy.SUM)
        )
      )
    )
    rejected("validation/labels") {
      scalar.join(
        Vector(
          new ScalarResult("a", 1, "#", AggregationPolicy.AVG),
          new ScalarResult("b", 2, "#", AggregationPolicy.AVG)
        )
      )
    }
    rejected("validation/units") {
      scalar.join(
        Vector(
          new ScalarResult("a", 1, "x", AggregationPolicy.AVG),
          new ScalarResult("a", 2, "y", AggregationPolicy.AVG)
        )
      )
    }
    rejected("validation/policies") {
      scalar.join(
        Vector(
          new ScalarResult("a", 1, "#", AggregationPolicy.AVG),
          new ScalarResult("a", 2, "#", AggregationPolicy.SUM)
        )
      )
    }
    val negativeZero =
      java.lang.Double.longBitsToDouble(Long.MinValue + params.getMeasurement().getBatchSize().toLong - 1)
    emit("statistics/zero-input", Vector(java.lang.Double.doubleToRawLongBits(negativeZero).toDouble))
    val zeroStatistics = new ListStatistics(Array(negativeZero, 0.0))
    emit(
      "statistics/zero-bits",
      Vector(
        java.lang.Double.doubleToRawLongBits(zeroStatistics.getMin()).toDouble,
        java.lang.Double.doubleToRawLongBits(zeroStatistics.getMax()).toDouble
      )
    )
    val empty = new ApiRun(params, Vector.empty[BenchmarkResult].asJava)
    emit("empty-run", Vector(if empty.getAggregatedResult() == null then 1.0 else 0.0))
    for
      (name, values) <- Vector(
        "cancellation" -> Array(1e16, 1.0, -1e16, 3.0),
        "nearby" -> Array(1e12, 1e12 + 1, 1e12 + 2, 1e12 + 3),
        "nan" -> Array(1.0, Double.NaN, 3.0),
        "infinite" -> Array(1.0, Double.PositiveInfinity, 3.0),
        "zeros" -> Array(negativeZero, 0.0, negativeZero)
      )
    do
      val list = new ListStatistics(values)
      val multi = new MultisetStatistics
      values.foreach(value => multi.addValue(value, 1L))
      for (kind, stats) <- Vector("list" -> list, "multiset" -> multi) do
        emit(
          s"statistics/$name/$kind",
          Vector(
            stats.getN().toDouble,
            stats.getMean(),
            stats.getSum(),
            stats.getMin(),
            stats.getMax(),
            stats.getVariance(),
            stats.getPercentile(0),
            stats.getPercentile(50),
            stats.getPercentile(100)
          )
        )

  end apply

end AggregationEdgeChecks

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(1)
class AggregationEdgeBenchmark:
  @Setup(Level.Trial) def verify(params: BenchmarkParams): Unit = AggregationEdgeChecks(params)
  @Benchmark def execute(): Int = 1

@State(Scope.Thread)
@AuxCounters(AuxCounters.Type.EVENTS)
class NumericEventEdges:
  var byteValue: Byte = 11
  var shortValue: Short = 11
  var intValue: Int = 11
  var longValue: Long = 11
  var floatValue: Float = 11
  var doubleValue: Double = 11
  var boxedByte: java.lang.Byte = java.lang.Byte.valueOf(11.toByte)
  var boxedShort: java.lang.Short = java.lang.Short.valueOf(11.toShort)
  var boxedInt: java.lang.Integer = java.lang.Integer.valueOf(11)
  var boxedLong: java.lang.Long = java.lang.Long.valueOf(11L)
  var boxedFloat: java.lang.Float = java.lang.Float.valueOf(11f)
  var boxedDouble: java.lang.Double = java.lang.Double.valueOf(11d)
  private var methodCount = 0L
  private var reads = 0

  def methodValue(): Long =
    reads += 1; methodCount + reads

  @Setup(Level.Iteration) def prepare(): Unit =
    byteValue = 100; shortValue = 100; intValue = 100; longValue = 100; floatValue = 100; doubleValue = 100
    boxedByte = 100.toByte; boxedShort = 100.toShort; boxedInt = 100; boxedLong = 100L; boxedFloat = 100f;
    boxedDouble = 100d
    methodCount = 200

  def tick(): Unit =
    // Scala public vars expose accessor methods to JVM JMH, rather than Java public fields.
    assert(byteValue >= 100 && boxedByte.byteValue() >= 100 && methodCount >= 200)
    add(1)

  private def add(n: Int): Unit =
    byteValue = (byteValue + n).toByte; shortValue = (shortValue + n).toShort
    intValue += n; longValue += n; floatValue += n; doubleValue += n
    boxedByte = (boxedByte.byteValue() + n).toByte; boxedShort = (boxedShort.shortValue() + n).toShort
    boxedInt = boxedInt.intValue() + n; boxedLong = boxedLong.longValue() + n
    boxedFloat = boxedFloat.floatValue() + n; boxedDouble = boxedDouble.doubleValue() + n
    methodCount += n

  @TearDown(Level.Iteration) def finishIteration(): Unit = add(3)

  @TearDown(Level.Trial) def finishTrial(): Unit =
    assert(reads == 2, "Counter getter was skipped or duplicated"); add(5)

end NumericEventEdges

@State(Scope.Thread)
@AuxCounters(AuxCounters.Type.OPERATIONS)
class ZeroOperationEdges:
  private var mode: Mode = Mode.All
  @Setup(Level.Trial) def prepare(params: BenchmarkParams): Unit = mode = params.getMode()

  def zeroOperations(): Long =
    assert(mode == Mode.Throughput || mode == Mode.AverageTime, "Getter for an omitted OPERATIONS metric was called")
    0L

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 1, batchSize = 2)
@Measurement(iterations = 2, batchSize = 3)
@Fork(1)
class NumericCounterEdgeBenchmark:

  @Benchmark def execute(events: NumericEventEdges, operations: ZeroOperationEdges): Int =
    events.tick(); 1

@State(Scope.Thread)
class ZeroCounterTarget:
  @Benchmark def execute(operations: ZeroOperationEdges): Int = 1

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(1)
class CounterApiEdgeProbe:

  @Benchmark def execute(): Int =
    for mode <- Vector(Mode.Throughput, Mode.AverageTime, Mode.SampleTime, Mode.SingleShotTime) do
      val result = new Runner(
        new OptionsBuilder()
          .include("microbench.examples.ZeroCounterTarget.execute")
          .mode(mode)
          .forks(0)
          .warmupIterations(0)
          .measurementIterations(2)
          .measurementTime(org.openjdk.jmh.runner.options.TimeValue.milliseconds(3))
          .shouldFailOnError(true)
          .verbosity(org.openjdk.jmh.runner.options.VerboseMode.SILENT)
          .build()
      ).runSingle()
      val counters = result.getSecondaryResults()
      if mode == Mode.Throughput || mode == Mode.AverageTime then
        val counter = counters.get("zeroOperations")
        assert(counter != null && counter.getSampleCount() == 2)
        if mode == Mode.Throughput then
          assert(counter.isInstanceOf[ThroughputResult] && counter.getScore() == 0)
          val raw = result
            .getBenchmarkResults()
            .iterator()
            .next()
            .getIterationResults()
            .iterator()
            .next()
            .getRawPrimaryResults()
            .iterator()
            .next()
          assert(raw.getScoreConfidence().forall(_.isFinite), "Raw throughput result lost SUM policy")
        else assert(counter.isInstanceOf[AverageTimeResult] && counter.getScore().isPosInfinity)
      else assert(!counters.containsKey("zeroOperations"), "Omitted metric leaked into public results")
    end for
    1

  end execute

end CounterApiEdgeProbe
