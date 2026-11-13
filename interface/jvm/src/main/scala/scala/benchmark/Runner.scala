package scala.benchmark

import org.openjdk.jmh.runner.options.{CommandLineOptions, Options}
import scala.benchmark.internal.JvmPreflight

/** Platform-aware entry point delegating JVM measurements to upstream JMH. */
class Runner(private val options: Options, private val format: org.openjdk.jmh.runner.format.OutputFormat) {
  def this(options: Options) = this(options, null)
  require(options != null, "Options not allowed to be null.")
  private def delegate(resolved: Options): org.openjdk.jmh.runner.Runner =
    if (format == null) new org.openjdk.jmh.runner.Runner(resolved)
    else new org.openjdk.jmh.runner.Runner(resolved, format)
  private def preflight(options: Options, listing: Boolean = false, parameters: Boolean = false): Option[Options] =
    try JvmPreflight.resolve(options, listing, parameters, format)
    catch {
      case error: IllegalArgumentException if error.getMessage.contains("No benchmarks match the selection") =>
        throw new org.openjdk.jmh.runner.NoBenchmarksException
      case error: IllegalArgumentException => throw new org.openjdk.jmh.runner.RunnerException(error.getMessage, error)
    }
  def run(): java.util.Collection[org.openjdk.jmh.results.RunResult] =
    preflight(options).fold(java.util.Collections.emptyList[org.openjdk.jmh.results.RunResult]())(resolved =>
      delegate(resolved).run()
    )
  def runSingle(): org.openjdk.jmh.results.RunResult =
    preflight(options).fold[org.openjdk.jmh.results.RunResult](
      throw new org.openjdk.jmh.runner.RunnerException("No results returned")
    )(resolved => delegate(resolved).runSingle())
  def list(): Unit = { preflight(options, listing = true); () }
  def listWithParams(): Unit = { preflight(options, listing = true, parameters = true); () }
  def listWithParams(options: CommandLineOptions): Unit = {
    preflight(options, listing = true, parameters = true); ()
  }
}
