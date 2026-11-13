package scala.benchmark

class Runner(options: org.openjdk.jmh.runner.options.Options, format: org.openjdk.jmh.runner.format.OutputFormat)
    extends org.openjdk.jmh.runner.Runner(options, format) {
  def this(options: org.openjdk.jmh.runner.options.Options) = this(options, null)
}
