package scala.benchmark.internal

trait RunnerService extends PlatformRunnerService {
  def renderResults(
      results: java.util.Collection[org.openjdk.jmh.results.RunResult],
      format: org.openjdk.jmh.results.format.ResultFormatType
  ): String
  def writeResultsFile(
      results: java.util.Collection[org.openjdk.jmh.results.RunResult],
      format: org.openjdk.jmh.results.format.ResultFormatType,
      file: String
  ): Unit
  def run(
      options: org.openjdk.jmh.runner.options.Options,
      format: org.openjdk.jmh.runner.format.OutputFormat = null
  ): java.util.Collection[org.openjdk.jmh.results.RunResult]
  def runSingle(
      options: org.openjdk.jmh.runner.options.Options,
      format: org.openjdk.jmh.runner.format.OutputFormat = null
  ): org.openjdk.jmh.results.RunResult
  def list(
      options: org.openjdk.jmh.runner.options.Options,
      parameters: Boolean,
      format: org.openjdk.jmh.runner.format.OutputFormat = null
  ): Unit
}
