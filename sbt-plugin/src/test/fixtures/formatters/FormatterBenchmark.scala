package scala.benchmark.runtime.formatters

import scala.benchmark.*
import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.results.{Result as Metric, ResultRole, AggregationPolicy}
import org.openjdk.jmh.runner.{IterationType, WorkloadParams}
import org.openjdk.jmh.results.format.{ResultFormatFactory, ResultFormatType}
import scala.jdk.CollectionConverters.*

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(0)
class FormatterBenchmark {
  @Benchmark def execute(): Int = {
    val formats = ResultFormatType.values().toVector
    // Java-style empty argument lists and the Scala facade must both remain usable.
    assert(org.openjdk.jmh.annotations.Mode.values().length == 5)
    assert(scala.benchmark.Mode.values().length == 5)
    assert(org.openjdk.jmh.annotations.Scope.values().length == 3)
    assert(scala.benchmark.Scope.values().length == 3)
    assert(org.openjdk.jmh.annotations.Level.values().length == 3)
    assert(scala.benchmark.Level.values().length == 3)
    assert(org.openjdk.jmh.annotations.AuxCounters.Type.values().length == 2)
    assert(scala.benchmark.AuxCounters.Type.values().length == 2)
    assert(org.openjdk.jmh.annotations.CompilerControl.Mode.values().length == 6)
    assert(scala.benchmark.CompilerControl.Mode.values().length == 6)
    assert(org.openjdk.jmh.runner.options.VerboseMode.values().length == 3)
    assert(scala.benchmark.VerboseMode.values().length == 3)
    assert(org.openjdk.jmh.runner.options.WarmupMode.values().length == 3)
    assert(scala.benchmark.WarmupMode.values().length == 3)
    assert(org.openjdk.jmh.runner.IterationType.values().length == 2)
    assert(org.openjdk.jmh.results.ResultRole.values().length == 4)
    assert(org.openjdk.jmh.results.AggregationPolicy.values().length == 4)
    assert(scala.benchmark.ResultFormatType.values().length == formats.size)
    val copied = ResultFormatType.values()
    copied(0) = ResultFormatType.JSON
    assert(ResultFormatType.values()(0) == ResultFormatType.TEXT)
    var cases = 0
    for (mode <- Vector(Mode.Throughput, Mode.AverageTime, Mode.SampleTime, Mode.SingleShotTime)) {
      val work = new WorkloadParams
      work.put("size", "λ,quoted\"value", 0)
      val params = new BenchmarkParams(
        "offline.example.ExternalBenchmark.execute",
        "offline.generated.target",
        true,
        1,
        Array(1),
        java.util.Collections.singletonList("worker"),
        2,
        0,
        new IterationParams(IterationType.WARMUP, 1, TimeValue.milliseconds(2), 2),
        new IterationParams(IterationType.MEASUREMENT, 2, TimeValue.milliseconds(3), 4),
        mode,
        work,
        TimeUnit.NANOSECONDS,
        1,
        "supplied-host",
        java.util.Collections.singletonList("supplied-arg"),
        "supplied-jdk",
        "supplied-vm",
        "supplied-version",
        "supplied-jmh",
        TimeValue.seconds(1)
      )
      val forks = (0 until 2).map { fork =>
        val iterations = (0 until 2).map { iteration =>
          val number = fork * 2 + iteration + 1
          val primary: Metric[?] = mode match {
            case Mode.Throughput =>
              new ThroughputResult(ResultRole.PRIMARY, "work", number * 10.0, 1L, TimeUnit.NANOSECONDS)
            case Mode.AverageTime =>
              new AverageTimeResult(ResultRole.PRIMARY, "work", 1.0, number * 10L, TimeUnit.NANOSECONDS)
            case Mode.SingleShotTime =>
              new SingleShotResult(ResultRole.PRIMARY, "work", number * 10L, TimeUnit.NANOSECONDS)
            case _ =>
              val buffer = new SampleBuffer
              buffer.add(number * 10L)
              new SampleTimeResult(ResultRole.PRIMARY, "work", buffer, TimeUnit.NANOSECONDS)
          }
          val result = new IterationResult(params, params.getMeasurement(), new IterationResultMetaData(100, 100))
          result.addResult(primary)
          result.addResult(new ScalarResult("count", number.toDouble, "#", AggregationPolicy.AVG))
          result
        }
        new BenchmarkResult(params, new java.util.ArrayList(iterations.asJava))
      }
      val run = new RunResult(params, new java.util.ArrayList(forks.asJava))
      val runs = new java.util.ArrayList[RunResult]
      runs.add(run)
      assert(run.getPrimaryResult().getScore() == 25.0)
      assert(run.getSecondaryResults().get("count").getScore() == 2.5)
      for (format <- formats) {
        val bytes = new ByteArrayOutputStream
        var flushes = 0
        var closes = 0
        val stream = new PrintStream(bytes, false, "UTF-8") {
          override def flush(): Unit = { flushes += 1; super.flush() }
          override def close(): Unit = { closes += 1; super.close() }
        }
        val writer = ResultFormatFactory.getInstance(format, stream)
        writer.writeOut(runs)
        val text = bytes.toString("UTF-8")
        assert(text.contains("execute") && text.contains("count"), format.toString + ": " + text)
        assert(flushes == 0 && closes == 0, "The caller owns the supplied stream")
        writer.writeOut(runs)
        assert(bytes.toString("UTF-8").length == text.length * 2)
        assert(run.getPrimaryResult().getScore() == 25.0 && run.getBenchmarkResults().size() == 2)
        val file = "factory-" + mode.shortLabel() + "." + format.name().toLowerCase
        val fileWriter = ResultFormatFactory.getInstance(format, file)
        fileWriter.writeOut(runs)
        if (format == ResultFormatType.JSON) {
          // writeOut reads the current collection; a reused file formatter overwrites its destination.
          runs.clear()
          fileWriter.writeOut(runs)
          ResultFormatFactory.getInstance(format, file + ".empty").writeOut(runs)
          runs.add(run)
          fileWriter.writeOut(runs)
        }
        cases += 1
      }
    }
    val empty = java.util.Collections.emptyList[RunResult]()
    val deferred = ResultFormatFactory.getInstance(ResultFormatType.JSON, "missing-parent/factory.json")
    assert(scala.util.Try(deferred.writeOut(empty)).failed.get.isInstanceOf[IllegalStateException])
    assert(
      scala.util
        .Try(ResultFormatFactory.getInstance(null, new PrintStream(new ByteArrayOutputStream)))
        .failed
        .get
        .isInstanceOf[NullPointerException]
    )
    val nullFile = ResultFormatFactory.getInstance(ResultFormatType.JSON, null: String)
    assert(scala.util.Try(nullFile.writeOut(empty)).failed.get.isInstanceOf[NullPointerException])
    val nullStream = ResultFormatFactory.getInstance(ResultFormatType.JSON, null: PrintStream)
    assert(scala.util.Try(nullStream.writeOut(empty)).failed.get.isInstanceOf[NullPointerException])
    val facade = scala.benchmark.ResultFormatFactory
      .getInstance(scala.benchmark.ResultFormatType.JSON, new PrintStream(new ByteArrayOutputStream))
    facade.writeOut(empty)
    println("RESULT_FORMAT_FACTORY_OK:" + cases)
    cases
  }
}

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(0)
class FormatterRawDisabledBenchmark {
  @Benchmark def execute(params: BenchmarkParams): Int = {
    System.setProperty("jmh.json.rawData", "false")
    val iteration = new IterationResult(params, params.getMeasurement(), new IterationResultMetaData(1, 1))
    iteration.addResult(new SingleShotResult(ResultRole.PRIMARY, "work", 10L, TimeUnit.NANOSECONDS))
    iteration.addResult(new ScalarResult("count", 1, "#", AggregationPolicy.AVG))
    val benchmark = new BenchmarkResult(params, java.util.Collections.singletonList(iteration))
    val result = new RunResult(params, java.util.Collections.singletonList(benchmark))
    val runs = java.util.Collections.singletonList(result)
    val bytes = new ByteArrayOutputStream
    val writer = ResultFormatFactory.getInstance(ResultFormatType.JSON, new PrintStream(bytes))
    System.setProperty("jmh.json.rawData", "true")
    writer.writeOut(java.util.Collections.emptyList[RunResult]())
    writer.writeOut(runs)
    assert(bytes.toString("UTF-8").contains("rawData"))
    System.setProperty("jmh.json.rawData", "true")
    // JMH reads this property when its JSON formatter is initialized, not on every writeOut.
    ResultFormatFactory.getInstance(ResultFormatType.JSON, "factory-raw-disabled.json").writeOut(runs)
    println("RESULT_FORMAT_RAW_DISABLED_OK")
    1
  }
}

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(0)
class FormatterDeferredJsonBenchmark {
  @Benchmark def execute(params: BenchmarkParams): Int = {
    System.setProperty("jmh.json.rawData", "false")
    val writer = ResultFormatFactory.getInstance(ResultFormatType.JSON, "factory-json-deferred.json")
    System.setProperty("jmh.json.rawData", "true")
    writer.writeOut(java.util.Collections.emptyList[RunResult]())
    System.setProperty("jmh.json.rawData", "false")
    val iteration = new IterationResult(params, params.getMeasurement(), new IterationResultMetaData(1, 1))
    iteration.addResult(new SingleShotResult(ResultRole.PRIMARY, "work", 10L, TimeUnit.NANOSECONDS))
    val benchmark = new BenchmarkResult(params, java.util.Collections.singletonList(iteration))
    writer.writeOut(
      java.util.Collections.singletonList(new RunResult(params, java.util.Collections.singletonList(benchmark)))
    )
    println("RESULT_FORMAT_JSON_INITIALIZATION_OK")
    1
  }
}
