package scala.scalanative.jmh.examples

import scala.benchmark.*
import org.openjdk.jmh.util.{ListStatistics, MultisetStatistics, Statistics}

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(0)
class StatisticsApiBenchmark {
  @Benchmark def execute(input: ApiDefinitions.Input): Int = {
    assert(input.ready)
    def rejected(body: => Any): Unit = {
      var failed = false
      try body
      catch { case _: IllegalArgumentException => failed = true }
      assert(failed)
    }
    for (multiset <- Vector(false, true)) {
      def samples(values: Double*): Statistics =
        if (!multiset) new ListStatistics(values.toArray)
        else { val stats = new MultisetStatistics; values.foreach(value => stats.addValue(value, 1L)); stats }
      val empty = samples()
      assert(empty.getMeanErrorAt(-1).isNaN)
      assert(samples(1, 2).getMeanErrorAt(2).isNaN)
      val regular = samples(1, 2, 3, 4)
      assert(regular.getMeanErrorAt(0) == 0)
      assert(regular.getMeanErrorAt(1) == Double.PositiveInfinity)
      assert(regular.getMeanErrorAt(-0.5) < 0)
      assert(math.abs(regular.getMeanErrorAt(0.95) - 2.054260256760879) < 1e-8)
      rejected(regular.getMeanErrorAt(3))
      rejected(empty.isDifferent(regular, 0.99))
      rejected(regular.isDifferent(samples(1), 0.99))
      rejected(regular.isDifferent(regular, 0.49))
      rejected(regular.isDifferent(regular, 1))
      assert(!samples(1, 1, 1).isDifferent(samples(2, 2, 2), 0.99))
      assert(regular.isDifferent(samples(101, 102, 103, 104), 0.99))
      assert(regular.compareTo(samples(101, 102, 103, 104), 0.99) < 0)
      assert(!regular.isDifferent(regular, 0.5))
      assert(samples(3, 0, 2, 1).getHistogram(Array(0.0, 2.0, 4.0)).toVector == Vector(2, 2))
      assert(samples(-1, 0, 1, 3).getHistogram(Array(0.0, 2.0, 4.0)).toVector == Vector(0, 0))
      assert(samples(0, 1, 5).getHistogram(Array(0.0, 2.0, 4.0)).toVector == Vector(2, 0))
      rejected(regular.getHistogram(Array(1.0)))
      assert(samples(1, 2, 3).getPercentile(0) == 1)
      assert(samples(1, 2, 3).getPercentile(50) == 2)
      assert(samples(1, 2, 3).getPercentile(100) == 3)
    }
    println("STATISTICS_API_OK")
    1
  }
}

object ApiDefinitions {
  @State(Scope.Thread) class Input {
    var ready = false
    @Setup def setup(): Unit = ready = true
  }
}
@State(Scope.Thread) class OtherApiInput
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(0)
class OverloadedApiBenchmark {
  @Benchmark @Group("left") def execute(input: ApiDefinitions.Input): Int = { assert(input.ready); 1 }
  @Benchmark @Group("right") def execute(input: OtherApiInput): Int = 2
}
