package org.openjdk.jmh.results

import scala.jdk.CollectionConverters.*

import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.infra.{BenchmarkParams, IterationParams}
import org.openjdk.jmh.util.{HashMultimap, Multimap, MultisetStatistics}

final class IterationResultMetaData(private val allOps: Long, private val measuredOps: Long) extends Serializable:
  def getAllOps(): Long = allOps
  def getMeasuredOps(): Long = measuredOps

final class BenchmarkResultMetaData(
    private var startTime: Long,
    private val measurementTime: Long,
    private val stopTime: Long,
    private val warmupOps: Long,
    private val measurementOps: Long
) extends Serializable:
  def getStartTime(): Long = startTime
  def getStopTime(): Long = stopTime
  def getWarmupTime(): Long = measurementTime - startTime
  def getMeasurementTime(): Long = stopTime - measurementTime
  def getWarmupOps(): Long = warmupOps
  def getMeasurementOps(): Long = measurementOps
  def adjustStart(value: Long): Unit = startTime = value

private[results] object ResultAggregation:

  def apply(values: Vector[Result[?]], mode: Mode, threads: Boolean): Result[?] =
    require(values.nonEmpty, "No result observations")
    values.head.aggregateResults(values, threads)

  def derivatives(primary: Result[?], secondary: java.util.Map[String, Result[?]]): java.util.Map[String, Result[?]] =
    val result = new java.util.TreeMap[String, Result[?]](secondary)
    (Vector(primary) ++ secondary.values().asScala).foreach { metric =>
      metric.derivativeResults.asScala.foreach(value => result.put(value.getLabel(), value))
    }
    result

  def secondary(
      values: Vector[Result[?]],
      mode: Mode,
      threads: Boolean,
      expected: Int = 0
  ): java.util.Map[String, Result[?]] =
    val result = new java.util.TreeMap[String, Result[?]]
    values.groupBy(_.getLabel()).toVector.sortBy(_._1).foreach { case (label, samples) =>
      val zero = samples.head.zeroResult
      val padded =
        if zero == null || samples.size >= expected then samples
        else samples ++ Vector.fill(expected - samples.size)(zero)
      result.put(label, apply(padded, mode, threads))
    }
    result

class IterationResult(
    private val benchmarkParams: BenchmarkParams,
    private val params: IterationParams,
    private val metadata: IterationResultMetaData
) extends Serializable:
  private val noPrimary = java.util.Collections.emptyList[Result[?]]()
  private var primary: java.util.Collection[Result[?]] = noPrimary
  private val secondary = new HashMultimap[String, Result[?]]
  def getBenchmarkParams(): BenchmarkParams = benchmarkParams
  def getParams(): IterationParams = params
  def getMetadata(): IterationResultMetaData = metadata

  def addResult(result: Result[?]): Unit =
    if result.getRole().isPrimary() then
      if primary eq noPrimary then primary = java.util.Collections.singleton(result)
      else if primary.size() == 1 then
        val expanded = new java.util.ArrayList[Result[?]](primary)
        expanded.add(result)
        primary = expanded
      else primary.add(result)
    else if result.getRole().isSecondary() then secondary.put(result.getLabel(), result)

  def addResults(results: java.util.Collection[? <: Result[?]]): Unit = results.asScala.foreach(addResult)
  def getRawPrimaryResults(): java.util.Collection[Result[?]] = primary
  def getRawSecondaryResults(): Multimap[String, Result[?]] = secondary

  def getPrimaryResult(): Result[?] =
    ResultAggregation(primary.asScala.toVector, benchmarkParams.getMode(), true)

  def getSecondaryResults(): java.util.Map[String, Result[?]] =
    ResultAggregation.derivatives(
      getPrimaryResult(),
      ResultAggregation.secondary(secondary.values().asScala.toVector, benchmarkParams.getMode(), true)
    )

  def getScoreUnit(): String = getPrimaryResult().getScoreUnit()

end IterationResult

class BenchmarkResult(
    private val params: BenchmarkParams,
    iterations: java.util.Collection[IterationResult],
    private val metadata: BenchmarkResultMetaData
) extends Serializable:
  def this(params: BenchmarkParams, iterations: java.util.Collection[IterationResult]) = this(params, iterations, null)
  private val results = iterations
  private val benchmarkResults = new HashMultimap[String, Result[?]]
  def getParams(): BenchmarkParams = params
  def getMetadata(): BenchmarkResultMetaData = metadata
  def getIterationResults(): java.util.Collection[IterationResult] = results
  def getBenchmarkResults(): Multimap[String, Result[?]] = benchmarkResults

  def addBenchmarkResult(result: Result[?]): Unit =
    benchmarkResults.put(result.getLabel(), result)

  def getPrimaryResult(): Result[?] =
    ResultAggregation(
      results.asScala.toVector
        .map(_.getPrimaryResult()) ++ benchmarkResults.values().asScala.filter(_.getRole().isPrimary()),
      params.getMode(),
      false
    )

  def getSecondaryResults(): java.util.Map[String, Result[?]] =
    val iterations = ResultAggregation.secondary(
      results.asScala.toVector.flatMap(_.getSecondaryResults().values().asScala.filterNot(_.getRole().isDerivative())),
      params.getMode(),
      false,
      results.size()
    )
    val combined = new java.util.TreeMap[String, Result[?]](iterations)
    ResultAggregation
      .secondary(
        benchmarkResults
          .values()
          .asScala
          .filter(r => r.getRole().isSecondary() && !r.getRole().isDerivative())
          .toVector,
        params.getMode(),
        false
      )
      .asScala
      .foreach { case (label, result) => combined.put(label, result) }
    ResultAggregation.derivatives(getPrimaryResult(), combined)

  def getScoreUnit(): String = getPrimaryResult().getScoreUnit()

end BenchmarkResult

class RunResult(private val params: BenchmarkParams, benchmarks: java.util.Collection[BenchmarkResult])
    extends Serializable:
  private val results = benchmarks
  def getParams(): BenchmarkParams = params
  def getBenchmarkResults(): java.util.Collection[BenchmarkResult] = results

  def getAggregatedResult(): BenchmarkResult =
    if results.isEmpty() then return null
    val combined = new BenchmarkResult(params, results.asScala.toVector.flatMap(_.getIterationResults().asScala).asJava)
    results.asScala.foreach(_.getBenchmarkResults().values().asScala.foreach(combined.addBenchmarkResult))
    combined

  def getPrimaryResult(): Result[?] = getAggregatedResult().getPrimaryResult()
  def getSecondaryResults(): java.util.Map[String, Result[?]] = getAggregatedResult().getSecondaryResults()

object RunResult:
  val DEFAULT_SORT_COMPARATOR: java.util.Comparator[RunResult] = (a, b) => a.getParams().compareTo(b.getParams())

/** Internal construction boundary: public objects retain raw observations as well as aggregate metrics. */
object ResultSnapshots:

  def iteration(
      params: BenchmarkParams,
      iteration: IterationParams,
      metadata: IterationResultMetaData,
      raw: java.util.Collection[Result[?]],
      rawSecondary: java.util.Collection[Result[?]],
      primary: Result[?],
      secondary: java.util.Map[String, Result[?]]
  ): IterationResult =
    val result = new IterationResult(params, iteration, metadata)
    result.addResults(raw); result.addResults(rawSecondary)
    result

  def benchmark(
      params: BenchmarkParams,
      iterations: java.util.Collection[IterationResult],
      primary: Result[?],
      secondary: java.util.Map[String, Result[?]],
      metadata: BenchmarkResultMetaData = null
  ): BenchmarkResult =
    val result = new BenchmarkResult(params, iterations, metadata)
    result

  def run(
      params: BenchmarkParams,
      forks: java.util.Collection[BenchmarkResult],
      primary: Result[?],
      secondary: java.util.Map[String, Result[?]]
  ): RunResult =
    new RunResult(params, forks)

end ResultSnapshots
