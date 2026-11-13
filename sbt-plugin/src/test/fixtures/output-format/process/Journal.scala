package scala.scalanative.jmh.outputformat

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths, StandardOpenOption}
import scala.jdk.CollectionConverters.*
object Journal {
  val workers = new java.util.concurrent.ConcurrentLinkedQueue[Thread]
  def worker(): Unit = { workers.add(Thread.currentThread()); () }
  def crash(): Unit = System.exit(23)
  def create(prefix: String): String = {
    val directory = Paths.get(System.getProperty("outputformat.evidence", System.getProperty("java.io.tmpdir")))
    Files.createDirectories(directory)
    Files.createTempFile(directory, prefix, ".trace").toString
  }
  def append(path: String, event: String): Unit = {
    Files.write(Paths.get(path), (event + "\n").getBytes(UTF_8), StandardOpenOption.APPEND)
    ()
  }
  def read(path: String): Vector[String] = Files.readAllLines(Paths.get(path)).asScala.toVector
}

object CancellationChecks {
  def verify(): Int = {
    import java.util.concurrent.{CountDownLatch, TimeUnit}
    import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
    import org.openjdk.jmh.infra.{BenchmarkParams, IterationParams}
    import org.openjdk.jmh.results.IterationResult
    import scala.benchmark.*
    import org.openjdk.jmh.runner.options.VerboseMode
    for (
      forks <- Vector(0, 1);
      event <- Vector("start:WARMUP:1", "result:WARMUP:1", "start:MEASUREMENT:1", "result:MEASUREMENT:2")
    ) {
      Journal.workers.clear()
      val entered = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val failure = new AtomicReference[Throwable]
      val interrupted = new AtomicBoolean
      val capture = new Capture {
        private def block(at: String): Unit = if (at == event) { entered.countDown(); release.await() }
        override def iteration(p: BenchmarkParams, i: IterationParams, n: Int): Unit = {
          super.iteration(p, i, n); block("start:" + i.getType() + ":" + n)
        }
        override def iterationResult(p: BenchmarkParams, i: IterationParams, n: Int, r: IterationResult): Unit = {
          super.iterationResult(p, i, n, r); block("result:" + i.getType() + ":" + n)
        }
      }
      val options = new OptionsBuilder()
        .include("scala.scalanative.jmh.outputformat.OutputModeTarget.execute")
        .mode(Mode.SingleShotTime)
        .forks(forks)
        .warmupIterations(1)
        .measurementIterations(2)
        .shouldFailOnError(false)
        .verbosity(VerboseMode.SILENT)
        .build()
      val caller = new Thread(() => {
        try new scala.benchmark.Runner(options, capture).run()
        catch { case error: Throwable => failure.set(error) }
        finally interrupted.set(Thread.currentThread().isInterrupted())
        ()
      })
      caller.setDaemon(true); caller.start()
      try {
        assert(entered.await(10, TimeUnit.SECONDS), event)
        caller.interrupt(); caller.join(5000)
        assert(!caller.isAlive, "Blocked runner: " + event)
        assert(failure.get() != null && interrupted.get(), "Lost cancellation: " + event)
        assert(Journal.workers.asScala.forall(!_.isAlive), "Leaked worker: " + event)
        assert(!capture.trace.contains("endRun"), capture.trace.toString)
      } finally { release.countDown(); caller.interrupt(); caller.join(5000) }
    }
    println("OUTPUT_FORMAT_CANCELLATION_OK:8")
    8
  }
}
