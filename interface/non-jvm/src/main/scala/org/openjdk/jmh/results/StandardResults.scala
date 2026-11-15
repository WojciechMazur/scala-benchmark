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

import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.runner.options.TimeValue
import org.openjdk.jmh.util.{ListStatistics, MultisetStatistics, SampleBuffer, SingletonStatistics, Statistics}

abstract private[results] class StandardResult[T <: Result[T]](
    role0: ResultRole,
    label0: String,
    statistics0: Statistics,
    unit0: String,
    policy0: AggregationPolicy
) extends Result[T](role0, label0, statistics0, unit0, policy0):

  protected def create(
      role: ResultRole,
      label: String,
      statistics: Statistics,
      unit: String,
      policy: AggregationPolicy
  ): T

  protected def threadPolicy: AggregationPolicy = policy
  protected def iterationPolicy: AggregationPolicy = policy
  protected def joinSamples: Boolean = false
  protected def thisResultIsScalar: Boolean = false

  private def aggregator(aggregation: AggregationPolicy): Aggregator[T] = new Aggregator[T]:
    def aggregate(results: java.util.Collection[T]): T =
      require(!results.isEmpty(), "No result observations")
      val values: org.openjdk.jmh.util.Statistics = if joinSamples then new MultisetStatistics else new ListStatistics
      val first = results.iterator().next()
      results.asScala.foreach { result =>
        if result.getScoreUnit() != first.getScoreUnit() then
          throw new IllegalStateException("Combining results with different units")
        if result.getLabel() != first.getLabel() || result.getRole() != first.getRole() then
          throw new IllegalStateException("Combining incompatible result labels or roles")
        if thisResultIsScalar && result.aggregationPolicy != first.aggregationPolicy then
          throw new IllegalStateException("Combining results with different policies")
        if joinSamples then
          result
            .getStatistics()
            .getRawData()
            .asScala
            .foreach(e =>
              values.asInstanceOf[MultisetStatistics].addValue(e.getKey.doubleValue(), e.getValue.longValue())
            )
        else values.asInstanceOf[ListStatistics].addValue(result.getScore())
      }
      create(
        first.getRole(),
        first.getLabel(),
        values,
        first.getScoreUnit(),
        if thisResultIsScalar then first.aggregationPolicy else aggregation
      )

  protected def getThreadAggregator(): Aggregator[T] = aggregator(threadPolicy)
  protected def getIterationAggregator(): Aggregator[T] = aggregator(iterationPolicy)

end StandardResult

class ThroughputResult private[results] (
    role0: ResultRole,
    label0: String,
    statistics0: Statistics,
    unit0: String,
    policy0: AggregationPolicy
) extends StandardResult[ThroughputResult](role0, label0, statistics0, unit0, policy0):

  def this(role: ResultRole, label: String, operations: Double, durationNs: Long, unit: TimeUnit) =
    this(
      role,
      label,
      new SingletonStatistics(operations * TimeUnit.NANOSECONDS.convert(1, unit) / durationNs),
      "ops/" + TimeValue.tuToString(unit),
      AggregationPolicy.SUM
    )

  override protected def threadPolicy: AggregationPolicy = AggregationPolicy.SUM
  override protected def iterationPolicy: AggregationPolicy = AggregationPolicy.AVG

  protected def create(
      role: ResultRole,
      label: String,
      statistics: Statistics,
      unit: String,
      policy: AggregationPolicy
  ): ThroughputResult =
    new ThroughputResult(role, label, statistics, unit, policy)

class AverageTimeResult private[results] (role0: ResultRole, label0: String, statistics0: Statistics, unit0: String)
    extends StandardResult[AverageTimeResult](role0, label0, statistics0, unit0, AggregationPolicy.AVG):

  def this(role: ResultRole, label: String, operations: Double, durationNs: Long, unit: TimeUnit) =
    this(
      role,
      label,
      new SingletonStatistics(durationNs / (operations * TimeUnit.NANOSECONDS.convert(1, unit))),
      TimeValue.tuToString(unit) + "/op"
    )

  protected def create(
      role: ResultRole,
      label: String,
      statistics: Statistics,
      unit: String,
      policy: AggregationPolicy
  ): AverageTimeResult =
    new AverageTimeResult(role, label, statistics, unit)

class SingleShotResult(role0: ResultRole, label0: String, statistics0: Statistics, unit0: String)
    extends StandardResult[SingleShotResult](role0, label0, statistics0, unit0, AggregationPolicy.AVG):
  override def extendedInfo(): String = distributionExtendedInfo()

  def this(role: ResultRole, label: String, durationNs: Long, operations: Long, unit: TimeUnit) =
    this(
      role,
      label,
      new SingletonStatistics(durationNs / (operations.toDouble * TimeUnit.NANOSECONDS.convert(1, unit))),
      TimeValue.tuToString(unit) + "/op"
    )

  def this(role: ResultRole, label: String, durationNs: Long, unit: TimeUnit) = this(role, label, durationNs, 1L, unit)

  protected def create(
      role: ResultRole,
      label: String,
      statistics: Statistics,
      unit: String,
      policy: AggregationPolicy
  ): SingleShotResult =
    new SingleShotResult(role, label, statistics, unit)

class ScalarResult private[results] (label0: String, statistics0: Statistics, unit0: String, policy0: AggregationPolicy)
    extends StandardResult[ScalarResult](ResultRole.SECONDARY, label0, statistics0, unit0, policy0):

  def this(label: String, value: Double, unit: String, policy: AggregationPolicy) =
    this(label, new SingletonStatistics(value), unit, policy)

  protected def create(
      role: ResultRole,
      label: String,
      statistics: Statistics,
      unit: String,
      policy: AggregationPolicy
  ): ScalarResult =
    new ScalarResult(label, statistics, unit, policy)

  override protected def thisResultIsScalar: Boolean = true
  override protected def getZeroResult(): ScalarResult = new ScalarResult(label, 0.0, unit, policy)

class ScalarDerivativeResult private[results] (
    label0: String,
    statistics0: Statistics,
    unit0: String,
    policy0: AggregationPolicy
) extends StandardResult[ScalarDerivativeResult](ResultRole.SECONDARY_DERIVATIVE, label0, statistics0, unit0, policy0):

  def this(label: String, value: Double, unit: String, policy: AggregationPolicy) =
    this(label, new SingletonStatistics(value), unit, policy)

  protected def create(
      role: ResultRole,
      label: String,
      statistics: Statistics,
      unit: String,
      policy: AggregationPolicy
  ): ScalarDerivativeResult =
    new ScalarDerivativeResult(label, statistics, unit, policy)

  override protected def thisResultIsScalar: Boolean = true
  override protected def getZeroResult(): ScalarDerivativeResult = new ScalarDerivativeResult(label, 0.0, unit, policy)
  override def extendedInfo(): String = ""

class SampleTimeResult private[results] (role0: ResultRole, label0: String, statistics0: Statistics, unit0: String)
    extends StandardResult[SampleTimeResult](role0, label0, statistics0, unit0, AggregationPolicy.AVG):

  def this(role: ResultRole, label: String, buffer: SampleBuffer, unit: TimeUnit) =
    this(
      role,
      label,
      buffer.getStatistics(1.0 / TimeUnit.NANOSECONDS.convert(1, unit)),
      TimeValue.tuToString(unit) + "/op"
    )

  override protected def joinSamples: Boolean = true

  protected def create(
      role: ResultRole,
      label: String,
      statistics: Statistics,
      unit: String,
      policy: AggregationPolicy
  ): SampleTimeResult =
    new SampleTimeResult(role, label, statistics, unit)

  override def extendedInfo(): String = distributionExtendedInfo()

  override protected def getDerivativeResults(): java.util.Collection[? <: Result[?]] =
    val prefix = if role.isPrimary() then "" else label + ":"
    Vector(
      "p0.00" -> 0.0,
      "p0.50" -> 50.0,
      "p0.90" -> 90.0,
      "p0.95" -> 95.0,
      "p0.99" -> 99.0,
      "p0.999" -> 99.9,
      "p0.9999" -> 99.99,
      "p1.00" -> 100.0
    ).map { case (name, point) =>
      new ScalarDerivativeResult(prefix + name, statistics.getPercentile(point), unit, AggregationPolicy.AVG)
    }.asJava

end SampleTimeResult

/** Runtime construction preserves the standard result classes without retaining generated harness objects. */
object StandardResults:

  def metric(
      mode: Mode,
      role: ResultRole,
      label: String,
      statistics: Statistics,
      unit: String,
      policy: AggregationPolicy,
      scalar: Boolean = false,
      derivative: Boolean = false
  ): Result[?] =
    if derivative then new ScalarDerivativeResult(label, statistics, unit, policy)
    else if scalar then new ScalarResult(label, statistics, unit, policy)
    else
      mode match
        case Mode.Throughput     => new ThroughputResult(role, label, statistics, unit, policy)
        case Mode.AverageTime    => new AverageTimeResult(role, label, statistics, unit)
        case Mode.SingleShotTime => new SingleShotResult(role, label, statistics, unit)
        case Mode.SampleTime     => new SampleTimeResult(role, label, statistics, unit)
        case Mode.All            => throw new IllegalArgumentException("All is not a measured mode")
