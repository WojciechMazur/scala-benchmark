package scala.benchmark.examples

import java.util.concurrent.TimeUnit

import scala.benchmark.*
import scala.jdk.CollectionConverters.*

import org.openjdk.jmh.infra.{BenchmarkParams, IterationParams}
import org.openjdk.jmh.runner.options.{CommandLineOptionException, CommandLineOptions, TimeValue, VerboseMode}

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(0)
class ConfigurationApiBenchmark:

  @Benchmark def execute(): Int =
    val parent = new OptionsBuilder()
      .include("parent")
      .exclude("excludedParent")
      .includeWarmup("warmParent")
      .param("size", "parent")
      .warmupIterations(0)
      .measurementIterations(3)
      .forks(0)
      .mode(Mode.Throughput)
    val child = new OptionsBuilder()
      .parent(parent.build())
      .include("child")
      .exclude("excludedChild")
      .includeWarmup("warmChild")
      .param("size", "a", "a")
      .param("size", "b")
      .measurementIterations(2)
      .mode(Mode.SingleShotTime)
      .mode(Mode.AverageTime)
      .build()
    assert(child.getIncludes().asScala.toVector == Vector("child", "parent"))
    assert(child.getExcludes().asScala.toVector == Vector("excludedChild", "excludedParent"))
    assert(child.getWarmupIncludes().asScala.toVector == Vector("warmChild", "warmParent"))
    assert(child.getParameter("size").get().asScala.toVector == Vector("a", "a", "b"))
    var immutableParameters = false
    try child.getParameter("size").get().add("rejected")
    catch case _: UnsupportedOperationException => immutableParameters = true
    assert(immutableParameters && child.getParameter("size").get().size() == 3)
    assert(child.getBenchModes().asScala.toVector == Vector(Mode.AverageTime, Mode.SingleShotTime))
    assert(child.getWarmupIterations().get().intValue() == 0)
    assert(child.getMeasurementIterations().get().intValue() == 2)
    assert(child.getForkCount().get().intValue() == 0)
    val cli = new CommandLineOptions("literal", "-p", "size=a,a", "-p", "size=b", "-bm", "ss,avgt", "-si", "TRUE")
    assert(cli.getParameter("size").get().asScala.toVector == Vector("a", "a", "b"))
    assert(cli.shouldSyncIterations().get().booleanValue())
    var rejected = false
    try new CommandLineOptions("-i", "1", "-i", "2")
    catch case _: CommandLineOptionException => rejected = true
    assert(rejected)
    val options = new OptionsBuilder()
      .include("scala.benchmark.examples.ConfigurationTarget.execute")
      .mode(Mode.SingleShotTime)
      .forks(0)
      .threads(1)
      .warmupIterations(1)
      .measurementIterations(2)
      .warmupBatchSize(4)
      .measurementBatchSize(2)
      .operationsPerInvocation(5)
      .timeUnit(TimeUnit.MILLISECONDS)
      .warmupTime(TimeValue.microseconds(7))
      .measurementTime(TimeValue.microseconds(9))
      .timeout(TimeValue.seconds(3))
      .verbosity(VerboseMode.SILENT)
      .build()
    val result = new Runner(options).runSingle()
    assert(result.getParams().getOpsPerInvocation() == 5)
    assert(result.getPrimaryResult().getStatistics().getN() == 2)
    new Runner(
      new OptionsBuilder()
        .include("scala.benchmark.examples.InheritedConfigurationTarget.execute")
        .threads(1)
        .shouldFailOnError(true)
        .verbosity(VerboseMode.SILENT)
        .build()
    ).runSingle()
    println("CONFIGURATION_API_OK")
    1

  end execute

end ConfigurationApiBenchmark

@BenchmarkMode(Array(Mode.AverageTime))
@Warmup(iterations = 3, batchSize = 7)
@Measurement(iterations = 7, batchSize = 3)
@OperationsPerInvocation(2)
@Threads(2)
@Fork(1)
class ConfigurationTarget:

  @Benchmark @OperationsPerInvocation(3) @Warmup(iterations = 2) def execute(
      p: BenchmarkParams,
      i: IterationParams
  ): Int =
    assert(p.getMode() == Mode.SingleShotTime && p.getThreads() == 1 && p.getForks() == 0)
    assert(p.getWarmup().getCount() == 1 && p.getMeasurement().getCount() == 2)
    assert(p.getWarmup().getBatchSize() == 4 && p.getMeasurement().getBatchSize() == 2)
    assert(p.getWarmup().getTime() == TimeValue.microseconds(7))
    assert(p.getMeasurement().getTime() == TimeValue.microseconds(9))
    assert(p.getTimeUnit() == TimeUnit.MILLISECONDS && p.getOpsPerInvocation() == 5)
    assert(!p.shouldSynchIterations())
    assert(i.getBatchSize() == 4 || i.getBatchSize() == 2)
    1

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 2, batchSize = 7, time = 2, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 2, batchSize = 3, time = 9, timeUnit = TimeUnit.MILLISECONDS)
@Fork(0)
abstract class ConfigurationBase

@Warmup(iterations = 1, timeUnit = TimeUnit.MICROSECONDS)
class InheritedConfigurationTarget extends ConfigurationBase:

  @Benchmark @Measurement(iterations = 1, timeUnit = TimeUnit.NANOSECONDS)
  def execute(p: BenchmarkParams): Int =
    assert(p.getWarmup().getCount() == 1 && p.getWarmup().getBatchSize() == 7)
    assert(p.getWarmup().getTime() == TimeValue.milliseconds(2))
    assert(p.getMeasurement().getCount() == 1 && p.getMeasurement().getBatchSize() == 3)
    assert(p.getMeasurement().getTime() == TimeValue.milliseconds(9))
    1
