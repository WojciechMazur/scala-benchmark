package scala.benchmark.internal

import org.openjdk.jmh.runner.options.CommandLineOptions

/** Project JVM entry point: platform preflight followed by upstream JMH. */
object JvmMain:

  def main(args: Array[String]): Unit =
    val options = new CommandLineOptions(args*)
    if options.shouldHelp() || options.shouldListResultFormats() || options.shouldListProfilers() then
      org.openjdk.jmh.Main.main(args)
    else
      val runner = new scala.benchmark.Runner(options)
      if options.shouldListWithParams() then runner.listWithParams(options)
      else if options.shouldList() then runner.list()
      else runner.run()
