package org.openjdk.jmh.runner

import scala.benchmark.internal.RunnerServices

import org.openjdk.jmh.runner.options.{CommandLineOptions, Options}

class RunnerException(message: String, cause: Throwable) extends Exception(message, cause):
  def this(message: String) = this(message, null)
  def this(cause: Throwable) = this(Option(cause).fold("")(_.getMessage), cause)

class NoBenchmarksException extends RunnerException("No benchmarks match the selection")

class Runner(private val options: Options, private val format: org.openjdk.jmh.runner.format.OutputFormat):
  def this(options: Options) = this(options, null)
  require(options != null, "Options not allowed to be null.")
  def run(): java.util.Collection[org.openjdk.jmh.results.RunResult] = RunnerServices.current.run(options, format)
  def runSingle(): org.openjdk.jmh.results.RunResult = RunnerServices.current.runSingle(options, format)
  def list(): Unit = RunnerServices.current.list(options, false, format)
  def listWithParams(): Unit = RunnerServices.current.list(options, true, format)
  def listWithParams(options: CommandLineOptions): Unit = RunnerServices.current.list(options, true, format)
