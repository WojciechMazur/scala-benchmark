package scala.benchmark.examples

import scala.compiletime.uninitialized

import org.openjdk.jmh.annotations.*

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(1)
class ParameterBenchmark:
  @Param var mode: Mode = uninitialized
  @Param(Array("true")) var flag: Boolean = false
  @Param(Array("a")) var character: Char = 0
  @Param(Array("2")) var boxed: java.lang.Integer = uninitialized
  @Param(Array("")) var text: String = uninitialized

  @Setup def check(): Unit =
    assert(
      mode != null && flag && character == 'a' &&
        boxed
          .intValue() == 2 && text == ""
    )

  @Benchmark def booleanResult(): Boolean = flag
  @Benchmark def byteResult(): Byte = character.toByte
  @Benchmark def charResult(): Char = character
  @Benchmark def shortResult(): Short = character.toShort
  @Benchmark def intResult(): Int = boxed.intValue()
  @Benchmark def longResult(): Long = boxed.longValue()
  @Benchmark def floatResult(): Float = boxed.floatValue()
  @Benchmark def doubleResult(): Double = boxed.doubleValue()
  @Benchmark def referenceResult(): String = text
