/*
 * Copyright (c) 2005, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.jmh.results

import org.openjdk.jmh.util.{ScoreFormatter, Statistics}

enum AggregationPolicy extends java.lang.Enum[AggregationPolicy]:
  case AVG, SUM, MAX, MIN

  override def toString(): String = this match
    case AVG => "Average"
    case SUM => "Sum"
    case MAX => "Maximum"
    case MIN => "Minimum"

enum ResultRole extends java.lang.Enum[ResultRole]:
  case PRIMARY, SECONDARY, SECONDARY_DERIVATIVE, OMITTED
  def isPrimary(): Boolean = this == PRIMARY
  def isSecondary(): Boolean = this == SECONDARY || this == SECONDARY_DERIVATIVE
  def isDerivative(): Boolean = this == SECONDARY_DERIVATIVE

trait Aggregator[T <: Result[?]]:
  def aggregate(results: java.util.Collection[T]): T

abstract class Result[T <: Result[T]](
    protected val role: ResultRole,
    label0: String,
    protected val statistics: Statistics,
    unit0: String,
    protected val policy: AggregationPolicy
) extends Serializable:
  protected val label: String = Result.dedup(label0)
  protected val unit: String = Result.dedup(unit0)
  def getRole(): ResultRole = role
  def getLabel(): String = label
  def getStatistics(): Statistics = statistics
  def getSampleCount(): Long = statistics.getN()
  final def getScoreUnit(): String = unit

  def getScore(): Double = policy match
    case AggregationPolicy.AVG => statistics.getMean()
    case AggregationPolicy.SUM => statistics.getSum()
    case AggregationPolicy.MIN => statistics.getMin()
    case AggregationPolicy.MAX => statistics.getMax()

  def getScoreError(): Double = if policy == AggregationPolicy.AVG then statistics.getMeanErrorAt(0.999) else Double.NaN

  def getScoreConfidence(): Array[Double] =
    if policy == AggregationPolicy.AVG then statistics.getConfidenceIntervalAt(0.999) else Array(getScore(), getScore())

  def extendedInfo(): String = simpleExtendedInfo()

  protected def simpleExtendedInfo(): String =
    val stats = getStatistics()
    val newline = System.lineSeparator()
    if stats.getN() > 2 && !ScoreFormatter.isApproximate(getScore()) then
      val interval = getScoreConfidence()
      s"  ${ScoreFormatter.format(getScore())} ±(99.9%) ${ScoreFormatter.formatError((interval(1) - interval(0)) / 2)} ${getScoreUnit()} [$policy]" +
        newline +
        s"  (min, avg, max) = (${ScoreFormatter.format(stats.getMin())}, ${ScoreFormatter.format(stats.getMean())}, ${ScoreFormatter.format(stats.getMax())}), stdev = ${ScoreFormatter.formatError(stats.getStandardDeviation())}" +
        newline +
        s"  CI (99.9%): [${ScoreFormatter.format(interval(0))}, ${ScoreFormatter.format(interval(1))}] (assumes normal distribution)" +
        newline
    else s"  ${ScoreFormatter.format(stats.getMean())} ${getScoreUnit()}" + newline

  protected def distributionExtendedInfo(): String =
    val stats = getStatistics()
    if stats.getN() <= 2 then return ""
    val interval = stats.getConfidenceIntervalAt(0.999)
    val text = new StringBuilder
    text.append("  N = ").append(stats.getN()).append("\n")
    text.append(
      s"  mean = ${ScoreFormatter.format(10, stats.getMean())} ±(99.9%) ${ScoreFormatter.formatError((interval(1) -
          interval(0)) / 2)} ${getScoreUnit()}\n"
    )
    text.append("\n  Histogram, ").append(getScoreUnit()).append(":\n")
    var min = stats.getMin()
    var max = stats.getMax()
    var binSize = math.pow(10, math.floor(math.log10(max - min)))
    min = math.floor(min / binSize) * binSize
    max = math.ceil(max / binSize) * binSize
    val range = max - min
    val levels = if range > 0 then
      while range / binSize < Result.histogramBins do binSize /= 2
      Array.tabulate(math.max(2, math.ceil(range / binSize).toInt))(i => min + i * binSize)
    else Array(stats.getMin() - math.ulp(stats.getMin()), stats.getMax() + math.ulp(stats.getMax()))
    val width = ScoreFormatter.format(1, max).length
    stats.getHistogram(levels).zipWithIndex.foreach { case (count, index) =>
      text.append(
        String.format(
          "    [%" + width + "s, %" + width + "s) = %d %n",
          ScoreFormatter.formatExact(width, levels(index)),
          ScoreFormatter.formatExact(width, levels(index + 1)),
          java.lang.Integer.valueOf(count)
        )
      )
    }
    text.append("\n  Percentiles, ").append(getScoreUnit()).append(":\n")
    Vector(0.0, 0.5, 0.9, 0.95, 0.99, 0.999, 0.9999, 0.99999, 0.999999, 1.0).foreach { p =>
      val label = "p(" + String.format("%.4f", java.lang.Double.valueOf(p * 100)) + ")"
      text.append(
        String.format(
          "    %11s = %s %s\n",
          label,
          ScoreFormatter.format(10, stats.getPercentile(p * 100)),
          getScoreUnit()
        )
      )
    }
    text.toString

  end distributionExtendedInfo

  private[results] def aggregationPolicy: AggregationPolicy = policy

  protected def getDerivativeResults(): java.util.Collection[? <: Result[?]] =
    java.util.Collections.emptyList[Result[?]]()

  final private[results] def derivativeResults: java.util.Collection[? <: Result[?]] = getDerivativeResults()
  protected def getThreadAggregator(): Aggregator[T]
  protected def getIterationAggregator(): Aggregator[T]
  protected def getZeroResult(): T = null.asInstanceOf[T]

  final private[results] def aggregateResults(values: Vector[Result[?]], threads: Boolean): Result[?] =
    import scala.jdk.CollectionConverters.*
    val aggregator = if threads then getThreadAggregator() else getIterationAggregator()
    if aggregator != null then aggregator.aggregate(values.asJava.asInstanceOf[java.util.Collection[T]])
    else if !threads && values.size == 1 then values.head
    else throw new IllegalStateException("No aggregator for " + label)

  final private[results] def zeroResult: Result[?] = getZeroResult()

  override def toString(): String =
    val error = getScoreError()
    if !error.isNaN && !ScoreFormatter.isApproximate(getScore()) then
      s"${ScoreFormatter.format(getScore())} ±(99.9%) ${ScoreFormatter.formatError(error)} ${getScoreUnit()}"
    else s"${ScoreFormatter.format(getScore())} ${getScoreUnit()}"

end Result

/** Immutable metric view populated with raw observations by the runtime. */
final class MetricResult(
    role0: ResultRole,
    label0: String,
    statistics0: Statistics,
    unit0: String,
    policy0: AggregationPolicy = AggregationPolicy.AVG
) extends Result[MetricResult](role0, label0, statistics0, unit0, policy0):

  private def aggregator = new Aggregator[MetricResult]:
    def aggregate(results: java.util.Collection[MetricResult]): MetricResult =
      import scala.jdk.CollectionConverters.*
      val values = new org.openjdk.jmh.util.ListStatistics
      results.asScala.foreach(r => values.addValue(r.getScore()))
      new MetricResult(role, label, values, unit, policy)

  protected def getThreadAggregator(): Aggregator[MetricResult] = aggregator
  protected def getIterationAggregator(): Aggregator[MetricResult] = aggregator

object Result:
  private lazy val histogramBins = org.openjdk.jmh.util.IntegerProperties.read("jmh.histogramBins", 10)
  private val strings = new java.util.concurrent.ConcurrentHashMap[String, String]

  private def dedup(value: String): String =
    if value == null then throw new NullPointerException("Result labels and units cannot be null")
    val previous = strings.putIfAbsent(value, value)
    if previous == null then value else previous

  def of(value: Double): Statistics = new org.openjdk.jmh.util.SingletonStatistics(value)

object AggregationPolicy:

  @scala.annotation.targetName("javaValues")
  def values(): Array[AggregationPolicy] = Array(AVG, SUM, MAX, MIN)

object ResultRole:

  @scala.annotation.targetName("javaValues")
  def values(): Array[ResultRole] = Array(PRIMARY, SECONDARY, SECONDARY_DERIVATIVE, OMITTED)
