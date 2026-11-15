package scala.benchmark.examples

import java.util.concurrent.TimeUnit

import scala.benchmark.*
import scala.compiletime.uninitialized

/** Compare decimal parsing with a counted loop and an iterator pipeline. */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@Threads(1)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
class ParameterBenchmark:
  @Param(Array("64", "1024")) var size: Int = 0
  private var records: Array[String] = uninitialized

  @Setup(Level.Trial) def prepare(): Unit =
    records = Array.tabulate(size)(i => ((i * 7919) % 1000000 - 500000).toString)

  @Benchmark def parseLoop(): Long =
    var total = 0L
    var i = 0
    while i < records.length do
      total += java.lang.Integer.parseInt(records(i))
      i += 1
    total

  @Benchmark def parseIterator(): Long = records.iterator.map(record => java.lang.Integer.parseInt(record).toLong).sum
