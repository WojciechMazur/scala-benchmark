package org.openjdk.jmh.runner.format

import org.openjdk.jmh.infra.{BenchmarkParams, IterationParams}
import org.openjdk.jmh.results.{BenchmarkResult, IterationResult, RunResult}

trait OutputFormat:
  def iteration(params: BenchmarkParams, iteration: IterationParams, index: Int): Unit
  def iterationResult(params: BenchmarkParams, iteration: IterationParams, index: Int, result: IterationResult): Unit
  def startBenchmark(params: BenchmarkParams): Unit
  def endBenchmark(result: BenchmarkResult): Unit
  def startRun(): Unit
  def endRun(results: java.util.Collection[RunResult]): Unit
  def print(text: String): Unit
  def println(text: String): Unit
  def verbosePrintln(text: String): Unit
  def write(value: Int): Unit
  def write(bytes: Array[Byte]): Unit
  def flush(): Unit
  def close(): Unit
