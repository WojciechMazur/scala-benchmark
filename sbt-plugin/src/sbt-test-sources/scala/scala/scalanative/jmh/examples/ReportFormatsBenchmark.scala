package scala.scalanative.jmh.examples

import java.util.concurrent.TimeUnit
import scala.benchmark.*

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@Warmup(iterations = 0)
@Measurement(iterations = 3, time = 10, timeUnit = TimeUnit.MILLISECONDS)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
class ReportFormatsBenchmark {
  @Param(Array("a;b,\"quoted\"\n<script>alert('x')</script>&_%#$price{}^~\\東京"))
  var payload: String = ""

  @Benchmark def execute(): Int = {
    var result = 1
    var i = 0
    while (i < payload.length) {
      result = 31 * result + payload.charAt(i)
      i += 1
    }
    result
  }
}
