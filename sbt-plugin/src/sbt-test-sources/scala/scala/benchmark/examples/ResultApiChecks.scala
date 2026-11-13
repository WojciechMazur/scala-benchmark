package scala.benchmark.examples

import scala.jdk.CollectionConverters.*

import org.openjdk.jmh.infra.BenchmarkParams
import org.openjdk.jmh.results.*
import org.openjdk.jmh.util.ListStatistics

object ResultApiChecks:

  private class Custom(value: Double)
      extends Result[Custom](
        ResultRole.PRIMARY,
        "custom",
        new ListStatistics(Array(value)),
        "#",
        AggregationPolicy.SUM
      ):
    protected def getThreadAggregator(): Aggregator[Custom] = aggregator(100)
    protected def getIterationAggregator(): Aggregator[Custom] = aggregator(1000)

    private def aggregator(offset: Double): Aggregator[Custom] = new Aggregator[Custom]:
      def aggregate(results: java.util.Collection[Custom]): Custom =
        new Custom(results.asScala.map(_.getScore()).sum + offset)

  def apply(params: BenchmarkParams): Unit =
    def iteration() = new IterationResult(params, params.getMeasurement(), new IterationResultMetaData(2, 2))
    val first = iteration()
    first.addResult(new Custom(1)); first.addResult(new Custom(2))
    assert(
      first.getPrimaryResult().isInstanceOf[Custom],
      "custom result type: " + first.getPrimaryResult().getClass.getName
    )
    assert(first.getPrimaryResult().getScore() == 103, "thread aggregate: " + first.getPrimaryResult().getScore())
    first.addResult(new ScalarResult("occasionally", 4, "#", AggregationPolicy.AVG))
    val second = iteration()
    second.addResult(new Custom(3))
    val benchmark = new BenchmarkResult(params, Vector(first, second).asJava)
    assert(
      benchmark.getPrimaryResult().getScore() == 1206,
      "iteration aggregate: " + benchmark.getPrimaryResult().getScore()
    )
    assert(
      benchmark.getSecondaryResults().get("occasionally").getScore() == 2,
      "missing secondary: " + benchmark.getSecondaryResults().get("occasionally").getScore()
    )
    benchmark.addBenchmarkResult(new ScalarResult("occasionally", 7, "#", AggregationPolicy.AVG))
    assert(
      benchmark.getSecondaryResults().get("occasionally").getScore() == 7,
      "benchmark secondary: " + benchmark.getSecondaryResults().get("occasionally").getScore()
    )
    // Constructors retain the caller's collection, and aggregates reflect later changes.
    val mutableIterations = new java.util.ArrayList[IterationResult]()
    mutableIterations.add(first)
    val live = new BenchmarkResult(params, mutableIterations)
    assert(live.getIterationResults() eq mutableIterations)
    assert(live.getPrimaryResult().getScore() == 1103)
    mutableIterations.add(second)
    assert(live.getPrimaryResult().getScore() == 1206)
    val forks = new java.util.ArrayList[BenchmarkResult]()
    forks.add(live)
    val run = new RunResult(params, forks)
    assert(run.getBenchmarkResults() eq forks)
    assert(run.getAggregatedResult().getIterationResults().size() == 2)
    forks.clear()
    assert(run.getAggregatedResult() == null)
    first.getRawPrimaryResults().add(new Custom(4))
    assert(first.getPrimaryResult().getScore() == 107)
    val metrics = live.getSecondaryResults()
    metrics.clear()
    assert(!live.getSecondaryResults().isEmpty())

    val multimap = new org.openjdk.jmh.util.TreeMultimap[String, String]()
    multimap.put("b", "first")
    val view = multimap.get("b")
    multimap.put("b", "second")
    assert(view.size() == 2)
    var immutable = false
    try view.add("rejected")
    catch case _: UnsupportedOperationException => immutable = true
    assert(immutable)
    val copiedValues = multimap.values()
    copiedValues.clear()
    assert(multimap.get("b").size() == 2)
    multimap.putAll("a", java.util.Collections.emptyList[String]())
    assert(multimap.keys().asScala.toVector == Vector("a", "b"))
    multimap.keys().remove("b")
    assert(multimap.get("b").isEmpty())

  end apply

end ResultApiChecks
