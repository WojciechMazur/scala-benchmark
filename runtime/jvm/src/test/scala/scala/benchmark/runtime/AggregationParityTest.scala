package scala.benchmark.runtime

import java.util.concurrent.TimeUnit

import scala.benchmark.internal.StudentT
import scala.jdk.CollectionConverters.*

import org.junit.Assert.*
import org.junit.Test
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.infra.ThreadParams
import org.openjdk.jmh.results.{
  AggregationPolicy,
  AverageTimeResult,
  BenchmarkResult as JmhBenchmarkResult,
  IterationResult as JmhIterationResult,
  IterationResultMetaData,
  Result as JmhResult,
  ResultRole,
  RunResult as JmhRunResult,
  SampleTimeResult,
  ScalarResult,
  SingleShotResult,
  ThroughputResult
}

class AggregationParityTest:
  private val modes = Vector(Mode.Throughput, Mode.AverageTime, Mode.SingleShotTime, Mode.SampleTime)

  private def number(label: String, expected: Double, actual: Double): Unit =
    if expected.isNaN then assertTrue(label, actual.isNaN)
    else if expected.isInfinity then assertEquals(label, expected, actual, 0)
    else if expected == 0 then assertEquals(label, 0.0, actual, 0.0)
    else assertTrue(s"$label: expected=$expected actual=$actual", math.abs((actual - expected) / expected) <= 1e-10)

  private def compare(label: String, expected: JmhResult[?], actual: RunResult): Unit =
    number(label + " score", expected.getScore, actual.score)
    // Commons Math's default inverse-CDF solver has an absolute 1e-9
    // quantile accuracy. Propagate that bound only to errors/intervals.
    val solverBound =
      1e-9 * expected.getStatistics.getStandardDeviation / math.sqrt(expected.getStatistics.getN.toDouble)
    def intervalNumber(name: String, a: Double, b: Double): Unit =
      if a.isFinite && b.isFinite && math.abs(a - b) <= solverBound then () else number(name, a, b)
    intervalNumber(label + " error", expected.getScoreError, actual.scoreError)
    assertEquals(label + " population", expected.getStatistics.getN, actual.statistics.count)
    number(label + " mean", expected.getStatistics.getMean, actual.statistics.mean)
    number(label + " variance", expected.getStatistics.getVariance, actual.statistics.variance)
    assertEquals(label + " unit", expected.getScoreUnit, actual.unit)
    expected.getScoreConfidence.zip(actual.scoreConfidence).foreach { case (a, b) =>
      intervalNumber(label + " confidence", a, b)
    }
    for percentile <- Vector(0.0, 50.0, 90.0, 99.9, 100.0) do
      number(
        label + " percentile " + percentile,
        expected.getStatistics.getPercentile(percentile),
        actual.statistics.percentile(percentile)
      )

  private def metric(
      mode: Mode,
      role: ResultRole,
      label: String,
      raw: RawResult,
      c: Configuration,
      operations: Double
  ): JmhResult[?] =
    val unit = TimeUnit.valueOf(c.unit)
    mode match
      case Mode.Throughput     => new ThroughputResult(role, label, operations, raw.elapsedNanos, unit)
      case Mode.AverageTime    => new AverageTimeResult(role, label, operations, raw.elapsedNanos, unit)
      case Mode.SingleShotTime =>
        new SingleShotResult(role, label, raw.elapsedNanos, c.operationsPerInvocation.toLong, unit)
      case Mode.SampleTime =>
        val buffer = new org.openjdk.jmh.util.SampleBuffer
        raw.samples.entries.foreach { case (value, count) => for _ <- 0L until count do buffer.add(value) }
        new SampleTimeResult(role, label, buffer, unit)
      case _ => throw new IllegalArgumentException("Unexpanded mode")

  private def upstreamWorkers(count: Int, groups: Array[Int]): Vector[ThreadParams] =
    val handler = Class.forName("org.openjdk.jmh.runner.BenchmarkHandler")
    val method = handler.getDeclaredMethod("distributeThreads", java.lang.Integer.TYPE, classOf[Array[Int]])
    method.setAccessible(true)
    method.invoke(null, Int.box(count), groups).asInstanceOf[java.util.List[ThreadParams]].asScala.toVector

  @Test def statisticsExtremaPreserveSignedZerosAndNaNs(): Unit =
    val numbers = Vector(-0.0, 0.0, -1.0, 1.0, Double.NaN, Double.NegativeInfinity, Double.PositiveInfinity)
    for a <- numbers; b <- numbers do
      assertEquals(
        java.lang.Double.doubleToLongBits(math.min(a, b)),
        java.lang.Double.doubleToLongBits(scala.benchmark.internal.StatisticsNumbers.minimum(a, b))
      )
      assertEquals(
        java.lang.Double.doubleToLongBits(math.max(a, b)),
        java.lang.Double.doubleToLongBits(scala.benchmark.internal.StatisticsNumbers.maximum(a, b))
      )

  @Test def confidenceQuantilesAgreeWithTightReferenceSolver(): Unit =
    for df <- Vector(2, 5, 17, 31, 64, 127, 255) do
      val exact = new org.apache.commons.math3.distribution.TDistribution(df.toDouble, 1e-13)
        .inverseCumulativeProbability(0.9995)
      assertEquals(s"df=$df", exact, StudentT.quantile(0.9995, df.toDouble), 1e-11)

  @Test def groupAssignmentsMatchUpstreamIncludingDisabledMembers(): Unit =
    for (count, groups) <- Vector(6 -> Array(1, 2), 1 -> Array(0, 1), 8 -> Array(2, 0, 2)) do
      val descriptor = Descriptor(
        0,
        "distribution",
        Vector(Mode.Throughput),
        Configuration(),
        Vector.empty,
        Vector.empty,
        members = groups.zipWithIndex.map { case (threads, i) =>
          GroupMember("member" + i, threads, Vector.empty)
        }.toVector
      )
      for (expected, index) <- upstreamWorkers(count, groups).zipWithIndex do
        val actual = descriptor.worker(index, count)
        def fields(p: ThreadParams): Vector[Int] = Vector(
          p.getThreadIndex,
          p.getThreadCount,
          p.getGroupIndex,
          p.getGroupCount,
          p.getSubgroupIndex,
          p.getSubgroupCount,
          p.getGroupThreadIndex,
          p.getGroupThreadCount,
          p.getSubgroupThreadIndex,
          p.getSubgroupThreadCount
        )
        assertEquals(s"count=$count worker=$index", fields(expected), fields(actual))

  @Test def unequalWorkersIterationsAndForksUseUpstreamAggregation(): Unit =
    val reference = UpstreamHarness(UpstreamHarness.Fixtures(false, false))
    reference.synchronized {
      for mode <- modes; grouped <- Vector(false, true) do
        val c = Configuration(
          measurementIterations = 2,
          threads = if grouped then 6 else 2,
          operationsPerInvocation = 7,
          measurementBatchSize = 3,
          unit = "MICROSECONDS"
        )
        val members =
          if grouped then Vector(GroupMember("read", 1, Vector.empty), GroupMember("write", 2, Vector.empty))
          else Vector.empty
        val descriptor =
          Descriptor(0, "aggregate.execute", Vector(mode), c, Vector.empty, Vector.empty, members = members)
        val assignments = upstreamWorkers(c.threads, if grouped then Array(1, 2) else Array(1))
        val params = Infrastructure.benchmark(descriptor, mode, c, Map.empty)
        val rawForks = Vector(1, 3, 2).zipWithIndex.map { case (length, fork) =>
          Vector.tabulate(length) { iteration =>
            val workers = Vector.tabulate(c.threads) { worker =>
              val buffer = new SampleBuffer
              buffer.addCount(11 + worker * 7, 1 + fork + iteration)
              buffer.addCount(57 + worker * 13, 2 + worker % 3)
              val invocations =
                if mode == Mode.SingleShotTime then 3L else (23L + worker) * (iteration + 1) * (fork + 1) * 3
              RawResult(invocations, 101L + worker * 127 + iteration * 19 + fork * 37, buffer)
            }
            IterationResult(Execution.aggregate(mode, c, workers), workers)
          }
        }
        val expectedForks = rawForks.map { iterations =>
          val results = iterations.map { iteration =>
            val result = new JmhIterationResult(params, params.getMeasurement, new IterationResultMetaData(1, 1))
            iteration.workers.zipWithIndex.foreach { case (raw, index) =>
              val operations =
                reference.normalizedScoreOperations(raw.invocations, c.measurementBatchSize, c.operationsPerInvocation)
              result.addResult(metric(mode, ResultRole.PRIMARY, "execute", raw, c, operations))
              if grouped then
                result.addResult(
                  metric(
                    mode,
                    ResultRole.SECONDARY,
                    members(assignments(index).getSubgroupIndex).name,
                    raw,
                    c,
                    operations
                  )
                )
            }
            result
          }
          new JmhBenchmarkResult(params, results.asJava)
        }
        val expected = new JmhRunResult(params, expectedForks.asJava)
        val actual = RunResult(descriptor, mode, c, Map.empty, rawForks, ClockCalibration(1, 1))
        compare(s"$mode grouped=$grouped", expected.getPrimaryResult, actual)
        if grouped then
          for label <- Vector("read", "write") do
            compare(
              s"$mode member=$label",
              expected.getSecondaryResults.get(label),
              actual.secondaryResults.toMap.apply(label)
            )
        if mode == Mode.SampleTime then
          for label <- Vector("p0.00", "p0.50", "p0.99", "p1.00") do
            number(
              label,
              expected.getSecondaryResults.get(label).getScore,
              actual.secondaryResults.toMap.apply(label).score
            )
    }

  end unequalWorkersIterationsAndForksUseUpstreamAggregation

  @Test def countersUseReferencePoliciesAtEveryAggregationLevel(): Unit =
    for mode <- modes do
      val c = Configuration(measurementIterations = 2, threads = 2, unit = "MICROSECONDS")
      val descriptor = Descriptor(
        0,
        "counters.execute",
        Vector(mode),
        c,
        Vector.empty,
        Vector.empty,
        counters = Vector(CounterDefinition("events", "EVENTS", 0), CounterDefinition("operations", "OPERATIONS", 0))
      )
      val params = Infrastructure.benchmark(descriptor, mode, c, Map.empty)
      val raw = Vector(1, 3, 2).zipWithIndex.map { case (length, fork) =>
        Vector.tabulate(length) { iteration =>
          val workers = Vector.tabulate(2) { worker =>
            val buffer = new SampleBuffer; buffer.addCount(11, 2)
            RawResult(
              23,
              101L + worker * 127,
              buffer,
              (if iteration % 2 == 0 && worker == 0 then
                 Vector(AuxiliaryValue("events", "EVENTS", 2 + fork + iteration + worker))
               else Vector.empty) ++
                (if iteration != 1 then Vector(AuxiliaryValue("operations", "OPERATIONS", 5 + worker * 3))
                 else Vector.empty)
            )
          }
          IterationResult(Execution.aggregate(mode, c, workers), workers)
        }
      }
      val expected = new JmhRunResult(
        params,
        raw.map { iterations =>
          new JmhBenchmarkResult(
            params,
            iterations.map { iteration =>
              val result = new JmhIterationResult(params, params.getMeasurement, new IterationResultMetaData(1, 1))
              iteration.workers.foreach { worker =>
                result.addResult(metric(mode, ResultRole.PRIMARY, "execute", worker, c, worker.invocations.toDouble))
                worker.auxiliary.foreach { counter =>
                  if counter.kind == "EVENTS" then
                    result.addResult(new ScalarResult("events", counter.value, "#", AggregationPolicy.SUM))
                  else if mode == Mode.Throughput || mode == Mode.AverageTime then
                    result.addResult(metric(mode, ResultRole.SECONDARY, "operations", worker, c, counter.value))
                }
              }
              result
            }.asJava
          )
        }.asJava
      )
      val actual = RunResult(descriptor, mode, c, Map.empty, raw, ClockCalibration(1, 1))
      compare(
        s"$mode events",
        expected.getSecondaryResults.get("events"),
        actual.secondaryResults.toMap.apply("events")
      )
      val expectedRawEvents =
        expected.getBenchmarkResults.asScala.toVector.map(_.getIterationResults.asScala.toVector.flatMap { iteration =>
          Option(iteration.getSecondaryResults.get("events")).map(_.getScore)
        })
      assertEquals(
        "Sparse event observations must retain JMH raw-data shape",
        expectedRawEvents,
        actual.secondaryResults.toMap.apply("events").forks.map(_.map(_.score))
      )
      if mode == Mode.Throughput || mode == Mode.AverageTime then
        compare(
          s"$mode operations",
          expected.getSecondaryResults.get("operations"),
          actual.secondaryResults.toMap.apply("operations")
        )

end AggregationParityTest
