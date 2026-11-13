package scala.benchmark.runtime

import org.openjdk.jmh.annotations.Mode

/** Internal interface/runtime boundary. Callback objects never cross a process boundary. */
trait RunObserver:
  def output[A](options: Options)(body: => A): A = ReportOutput(options)(body)
  def displaysResults: Boolean = false
  def diagnosticOutput: java.io.PrintStream = Console.out
  def reportResults(results: Vector[RunResult]): Vector[RunResult] = results
  def startRun(): Unit = ()
  def startBenchmark(d: Descriptor, mode: Mode, c: Configuration, p: Map[String, String]): Unit = ()
  def trial(d: Descriptor, mode: Mode, c: Configuration, p: Map[String, String]): TrialObserver = TrialObserver.Disabled

  def warmup(index: Int, d: Descriptor, mode: Mode, c: Configuration, p: Map[String, String]): TrialObserver =
    startBenchmark(d, mode, c, p)
    trial(d, mode, c, p)

  def endBenchmark(result: Option[RunResult], forked: Boolean): Unit = ()
  def endRun(results: Vector[RunResult]): Unit = ()
  def flush(): Unit = ()
  def close(): Unit = ()

object RunObserver:
  object Disabled extends RunObserver
