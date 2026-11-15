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
package org.openjdk.jmh.util

import scala.jdk.CollectionConverters.*

trait Statistics
    extends org.apache.commons.math3.stat.descriptive.StatisticalSummary
    with Serializable
    with Comparable[Statistics]:
  def getN(): Long
  def getMean(): Double
  def getSum(): Double
  def getMin(): Double
  def getMax(): Double
  def getVariance(): Double
  def getStandardDeviation(): Double = math.sqrt(getVariance())
  def getPercentile(percentile: Double): Double
  def getRawData(): java.util.Iterator[java.util.Map.Entry[java.lang.Double, java.lang.Long]]

  def getMeanErrorAt(confidence: Double): Double =
    if getN() <= 2 then Double.NaN
    else
      scala.benchmark.internal.StudentT.quantile((1 + confidence) / 2, (getN() - 1).toDouble) *
        getStandardDeviation() / math.sqrt(getN().toDouble)

  def getConfidenceIntervalAt(confidence: Double): Array[Double] =
    val error = getMeanErrorAt(confidence)
    Array(getMean() - error, getMean() + error)

  def getHistogram(levels: Array[Double]): Array[Int] =
    require(levels.length >= 2, "Histogram needs at least two boundaries")
    val result = new Array[Int](levels.length - 1)
    // JMH traverses sorted observations once, advancing (and never rewinding) the bin cursor.
    val sorted = getRawData().asScala.toVector.sortWith((a, b) =>
      java.lang.Double.compare(a.getKey.doubleValue(), b.getKey.doubleValue()) < 0
    )
    var bin = 0
    var exhausted = false
    sorted.foreach { entry =>
      if !exhausted then
        val value = entry.getKey.doubleValue()
        while !exhausted && (levels(bin) > value || value >= levels(bin + 1)) do
          bin += 1
          exhausted = bin >= result.length
        if !exhausted then result(bin) += entry.getValue.intValue()
    }
    result

  def isDifferent(other: Statistics, confidence: Double): Boolean =
    require(other != null, "Null statistics")
    require(confidence >= 0.5 && confidence < 1, "Confidence must be in [0.5, 1)")
    require(getN() >= 2 && other.getN() >= 2, "A t-test needs at least two observations per sample")
    val a = getVariance() / getN()
    val b = other.getVariance() / other.getN()
    val degrees =
      (a + b) *
        (a + b) /
        (a * a / (getN() - 1) + b * b / (other.getN() - 1))
    if degrees.isNaN || a.isNaN || b.isNaN then false
    else
      math.abs(getMean() - other.getMean()) >
        scala.benchmark.internal.StudentT.quantile((1 + confidence) / 2, degrees) * math.sqrt(a + b)

  def compareTo(other: Statistics, confidence: Double): Int =
    if isDifferent(other, confidence) then java.lang.Double.compare(getMean(), other.getMean()) else 0

  def compareTo(other: Statistics): Int = compareTo(other, 0.99)

end Statistics

abstract class AbstractStatistics extends Statistics:
  def getMean(): Double = if getN() == 0 then Double.NaN else getSum() / getN()

  override def toString(): String =
    s"N:${getN()} Mean: ${getMean()} Min: ${getMin()} Max: ${getMax()} StdDev: ${getStandardDeviation()}"

class MultisetStatistics extends AbstractStatistics:
  private val observations = scala.collection.mutable.TreeMap.empty[Double, Long]

  def addValue(value: Double, count: Long): Unit =
    val updated = observations.getOrElse(value, 0L) + count
    if updated == 0L then observations.remove(value) else observations(value) = updated

  def addValue(value: Double): Unit = addValue(value, 1L)
  def getN(): Long = observations.values.sum

  def getSum(): Double =
    if observations.isEmpty then Double.NaN else observations.iterator.map { case (value, count) => value * count }.sum

  def getMin(): Double = if observations.isEmpty then Double.NaN
  else observations.keysIterator.foldLeft(Double.PositiveInfinity)(scala.benchmark.internal.StatisticsNumbers.minimum)

  def getMax(): Double = if observations.isEmpty then Double.NaN
  else observations.keysIterator.foldLeft(Double.NegativeInfinity)(scala.benchmark.internal.StatisticsNumbers.maximum)

  def getVariance(): Double =
    val n = getN()
    if n <= 1 then Double.NaN
    else
      val mean = getMean()
      val sum = observations.iterator.map { case (v, count) => (v - mean) * (v - mean) * count }.sum
      sum / (n - 1)

  def getPercentile(percent: Double): Double =
    require(!(percent < 0 || percent > 100), "Percentile is outside [0, 100]")
    val n = getN()
    def at(rank: Long): Double =
      var count = 0L
      observations.iterator.find { case (_, weight) => count += weight; count >= rank }.get._1
    if percent == 0 then getMin()
    else if n == 0 then Double.NaN
    else
      val position = percent * (n + 1) / 100
      val lower = math.floor(position).toLong
      def value(rank: Long): Double = if rank > n then getMax() else at(rank)
      val first = value(lower)
      first + (value(lower + 1) - first) * (position - lower)

  def getRawData(): java.util.Iterator[java.util.Map.Entry[java.lang.Double, java.lang.Long]] =
    observations.iterator.map { case (v, n) =>
      new java.util.AbstractMap.SimpleImmutableEntry[java.lang.Double, java.lang.Long](v, n): java.util.Map.Entry[
        java.lang.Double,
        java.lang.Long
      ]
    }.asJava

end MultisetStatistics

class ListStatistics extends AbstractStatistics:
  private val values = scala.collection.mutable.ArrayBuffer.empty[Double]

  def this(samples: Array[Double]) =
    this(); samples.foreach(addValue)

  def this(samples: Array[Long]) =
    this(); samples.foreach(v => addValue(v.toDouble))

  def addValue(value: Double): Unit = values += value
  def getN(): Long = values.size.toLong
  def getSum(): Double = if values.isEmpty then Double.NaN else values.sum

  def getMin(): Double = if values.isEmpty then Double.NaN
  else values.foldLeft(Double.PositiveInfinity)(scala.benchmark.internal.StatisticsNumbers.minimum)

  def getMax(): Double = if values.isEmpty then Double.NaN
  else values.foldLeft(Double.NegativeInfinity)(scala.benchmark.internal.StatisticsNumbers.maximum)

  def getVariance(): Double =
    if values.size <= 1 then Double.NaN
    else
      val mean = getMean(); values.iterator.map(v => (v - mean) * (v - mean)).sum / (values.size - 1)

  def getPercentile(percent: Double): Double =
    if values.isEmpty then Double.NaN
    else
      if percent.isNaN then throw new ArrayIndexOutOfBoundsException(-1)
      require(percent >= 0 && percent <= 100, "Percentile is outside [0, 100]")
      if percent == 0 then return getMin()
      val sorted = values.filterNot(_.isNaN).sorted
      val position = percent * (sorted.size + 1) / 100
      if sorted.isEmpty then Double.NaN
      else if position < 1 then sorted.head
      else if position >= sorted.size then sorted.last
      else
        val lower = position.toInt; sorted(lower - 1) + (position - lower) * (sorted(lower) - sorted(lower - 1))

  def getRawData(): java.util.Iterator[java.util.Map.Entry[java.lang.Double, java.lang.Long]] =
    values.iterator
      .map(v =>
        new java.util.AbstractMap.SimpleImmutableEntry[java.lang.Double, java.lang.Long](v, 1L): java.util.Map.Entry[
          java.lang.Double,
          java.lang.Long
        ]
      )
      .asJava

end ListStatistics
