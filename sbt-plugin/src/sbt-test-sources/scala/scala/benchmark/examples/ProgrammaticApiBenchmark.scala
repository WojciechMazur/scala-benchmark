package scala.benchmark.examples

import scala.benchmark.*
import org.openjdk.jmh.runner.options.TimeValue

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(0)
class ProgrammaticApiBenchmark {
  @Benchmark def execute(): Int = {
    val statistics: org.openjdk.jmh.util.AbstractStatistics =
      new org.openjdk.jmh.util.ListStatistics(Array[Long](3, 1, 3))
    val summary: org.apache.commons.math3.stat.descriptive.StatisticalSummary = statistics
    assert(summary.getN() == 3 && summary.getSum() == 7.0)
    assert(statistics.getRawData().next().getKey().doubleValue() == 3.0)
    assert(new org.openjdk.jmh.util.ListStatistics().getSum().isNaN)
    val parent = new OptionsBuilder().warmupIterations(0).measurementIterations(1).forks(0).threads(1)
    val options = new OptionsBuilder()
      .parent(parent.build())
      .include("literal.bench.CanonicalBenchmark.execute")
      .param("unused", "ignored")
      .mode(Mode.SingleShotTime)
      .timeout(TimeValue.seconds(5))
      .measurementTime(TimeValue.microseconds(7))
      .build()
    assert(options.getWarmupIterations().get().intValue() == 0)
    val result = new Runner(options).runSingle()
    assert(result.getParams().getBenchmark() == "literal.bench.CanonicalBenchmark.execute")
    assert(result.getParams().getMeasurement().getTime() == TimeValue.microseconds(7))
    assert(result.getParams().getTimeout() == TimeValue.seconds(5))
    assert(result.getParams().id().nonEmpty)
    ResultApiChecks(result.getParams())
    val primary = result.getPrimaryResult()
    assert(primary.getScore().isFinite && primary.getScore() >= 0)
    assert(primary.getStatistics().getN() == 1)
    assert(primary.getStatistics().getRawData().hasNext())
    assert(result.getBenchmarkResults().size() == 1)
    val iteration = result.getBenchmarkResults().iterator().next().getIterationResults().iterator().next()
    assert(iteration.getRawPrimaryResults().size() == 1)
    assert(iteration.getMetadata().getMeasuredOps() == 1)
    val fork = result.getBenchmarkResults().iterator().next()
    assert(fork.getMetadata() != null)
    assert(fork.getMetadata().getStopTime() >= fork.getMetadata().getStartTime())
    assert(!result.getParams().shouldSynchIterations())
    assert(primary.isInstanceOf[org.openjdk.jmh.results.SingleShotResult])
    val events = new Runner(
      new OptionsBuilder()
        .parent(parent.build())
        .include("scala.benchmark.examples.CounterBenchmark.execute")
        .warmupIterations(1)
        .measurementIterations(2)
        .verbosity(org.openjdk.jmh.runner.options.VerboseMode.SILENT)
        .build()
    ).runSingle()
    assert(events.getSecondaryResults().get("events").getScore() == 23.0)
    assert(events.getSecondaryResults().get("events").isInstanceOf[org.openjdk.jmh.results.ScalarResult])
    for (mode <- Array(Mode.Throughput, Mode.AverageTime, Mode.SampleTime)) {
      val measured = new Runner(
        new OptionsBuilder()
          .parent(parent.build())
          .include("literal.bench.CanonicalBenchmark.execute")
          .mode(mode)
          .measurementTime(TimeValue.milliseconds(2))
          .verbosity(org.openjdk.jmh.runner.options.VerboseMode.SILENT)
          .build()
      ).runSingle()
      mode match {
        case Mode.Throughput =>
          assert(measured.getPrimaryResult().isInstanceOf[org.openjdk.jmh.results.ThroughputResult])
        case Mode.AverageTime =>
          assert(measured.getPrimaryResult().isInstanceOf[org.openjdk.jmh.results.AverageTimeResult])
        case Mode.SampleTime =>
          assert(measured.getPrimaryResult().isInstanceOf[org.openjdk.jmh.results.SampleTimeResult])
          assert(measured.getSecondaryResults().containsKey("p0.50"))
        case _ => ()
      }
    }
    val invalid = new OptionsBuilder()
      .parent(parent.build())
      .include("scala.benchmark.examples.PlatformBenchmarks.everywhere")
      .include("scala.benchmark.examples.PlatformBenchmarks.nativeOnly")
      .include("scala.benchmark.examples.PlatformBenchmarks.jvmOnly")
      .build()
    var rejected = false
    try new Runner(invalid).run()
    catch { case error: RunnerException => rejected = error.getMessage.contains("unavailable") }
    assert(rejected)
    new Runner(new OptionsBuilder().include("scala.benchmark.examples.PlatformBenchmarks.nativeOnly").build())
      .list()
    1
  }
}
