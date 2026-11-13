package scala.scalanative.jmh.outputformat

import scala.benchmark.*
import org.openjdk.jmh.infra.{BenchmarkParams, IterationParams}
import org.openjdk.jmh.results.{BenchmarkResult, IterationResult, RunResult}
import org.openjdk.jmh.runner.format.OutputFormat
import org.openjdk.jmh.runner.options.VerboseMode
import scala.jdk.CollectionConverters.*

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.SingleShotTime))
class OutputTarget {
  @Param(Array("none")) var failure: String = ""
  @Param(Array("unused")) var journal: String = ""
  private var measured = 0
  @Setup def setup(): Unit = Journal.append(journal, "setupTrial")
  @TearDown def teardown(): Unit = Journal.append(journal, "teardownTrial")
  @Benchmark def execute(params: IterationParams): Int = {
    val measurement = params.getType() == org.openjdk.jmh.runner.IterationType.MEASUREMENT
    if (measurement) measured += 1
    Journal.append(journal, "work:" + params.getType() + ":" + measured)
    if (measurement && failure == "crash" && measured == 2) Journal.crash()
    if (measurement && (failure == "early" || (failure == "late" && measured == 2)))
      throw new IllegalStateException("output-format-" + failure)
    1
  }
}
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(0)
class OutputFormatApiBenchmark {
  @Benchmark def execute(): Int = {
    for (forks <- Vector(0, 1); failure <- Vector("none", "early", "late")) {
      val journal = Journal.create(s"forks-$forks-$failure-")
      def record(event: String): Unit = Journal.append(journal.toString, event)
      val format = new OutputFormat {
        def startRun(): Unit = record("startRun")
        def endRun(results: java.util.Collection[RunResult]): Unit = record("endRun:" + results.size())
        def startBenchmark(params: BenchmarkParams): Unit = record("startBenchmark")
        def endBenchmark(result: BenchmarkResult): Unit = record(
          "endBenchmark:" + (if (result == null) "null" else result.getIterationResults().size().toString)
        )
        def iteration(params: BenchmarkParams, iteration: IterationParams, index: Int): Unit =
          record("iteration:" + iteration.getType() + ":" + index)
        def iterationResult(
            params: BenchmarkParams,
            iteration: IterationParams,
            index: Int,
            result: IterationResult
        ): Unit =
          record("iterationResult:" + iteration.getType() + ":" + index + ":" + result.getMetadata().getMeasuredOps())
        def print(text: String): Unit = ()
        def println(text: String): Unit = ()
        def verbosePrintln(text: String): Unit = ()
        def write(value: Int): Unit = ()
        def write(bytes: Array[Byte]): Unit = ()
        def flush(): Unit = record("flush")
        def close(): Unit = record("close")
      }
      val options = new OptionsBuilder()
        .include("scala.scalanative.jmh.outputformat.OutputTarget.execute")
        .mode(Mode.SingleShotTime)
        .warmupIterations(1)
        .measurementIterations(2)
        .forks(forks)
        .threads(1)
        .shouldFailOnError(false)
        .verbosity(VerboseMode.SILENT)
        .param("failure", failure)
        .param("journal", journal.toString)
        .build()
      try {
        val results = new org.openjdk.jmh.runner.Runner(options, format).run()
        record("returned:" + results.size())
      } catch { case error: Throwable => record("threw:" + error.getClass.getName) }
      val trace = Journal.read(journal)
      println(s"OUTPUT_FORMAT_TRACE:$forks:$failure:" + trace.mkString(","))
      val callbackTrace = trace
        .filter(e =>
          e == "startRun" || e == "startBenchmark" || e.startsWith("iteration") ||
            e.startsWith("endBenchmark") || e.startsWith("endRun") || e == "flush" || e == "close" ||
            e.startsWith("returned:") || e.startsWith("threw:")
        )
        .map(e => if (e.startsWith("threw:")) "threw" else e)
      val prefix = Vector(
        "startRun",
        "startBenchmark",
        "iteration:WARMUP:1",
        "iterationResult:WARMUP:1:1",
        "iteration:MEASUREMENT:1"
      )
      val workerClose = if (forks == 1) Vector("flush", "close") else Vector.empty
      val expected = failure match {
        case "none" =>
          prefix ++ Vector(
            "iterationResult:MEASUREMENT:1:1",
            "iteration:MEASUREMENT:2",
            "iterationResult:MEASUREMENT:2:1"
          ) ++
            workerClose ++ Vector("endBenchmark:2", "endRun:1", "flush", "close", "returned:1")
        case "early" =>
          prefix ++ workerClose ++ (if (forks == 1) Vector("endBenchmark:null") else Vector.empty) ++
            Vector("endRun:0", "flush", "close", "returned:0")
        case "late" if forks == 0 =>
          prefix ++ Vector("iterationResult:MEASUREMENT:1:1", "iteration:MEASUREMENT:2", "threw")
        case "late" =>
          prefix ++ Vector("iterationResult:MEASUREMENT:1:1", "iteration:MEASUREMENT:2") ++ workerClose ++
            Vector("endBenchmark:1", "endRun:1", "flush", "close", "returned:1")
      }
      assert(callbackTrace == expected, s"forks=$forks failure=$failure callbacks=$callbackTrace expected=$expected")
      assert(trace.head == "startRun", trace.mkString(","))
      assert(trace.contains("startBenchmark"))
      assert(trace.contains("iteration:WARMUP:1"))
      assert(trace.contains("iterationResult:WARMUP:1:1"))
      if (forks == 0) assert(trace.indexOf("iteration:WARMUP:1") < trace.indexOf("setupTrial"))
      if (failure == "none") {
        assert(trace.contains("endBenchmark:2"))
        assert(trace.contains("endRun:1"))
        assert(trace.contains("returned:1"))
        assert(trace.indexOf("teardownTrial") < trace.indexOf("iterationResult:MEASUREMENT:2:1"))
      }
    }
    println("OUTPUT_FORMAT_REFERENCE_OK")
    1
  }
}
