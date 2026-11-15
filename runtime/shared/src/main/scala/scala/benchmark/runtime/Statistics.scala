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
package scala.benchmark.runtime

import scala.benchmark.internal.StudentT

final class SampleBuffer extends scala.benchmark.internal.SamplingHistogram

final case class Statistics(values: Vector[(Double, Long)], sampled: Boolean = false):
  val count: Long = values.map(_._2).sum

  val mean: Double =
    if count == 0 then Double.NaN
    else values.map { case (v, n) => v * n }.sum / count

  val variance: Double =
    if count <= 1 then Double.NaN
    else
      val sum = values.map { case (v, n) => (v - mean) * (v - mean) * n }.sum
      sum / (count - 1)

  val deviation: Double = math.sqrt(variance)

  def error: Double =
    if count <= 2 then Double.NaN
    else
      StudentT.quantile(0.9995, (count - 1).toDouble) * deviation / math.sqrt(count.toDouble)

  private lazy val minimum =
    values.iterator.map(_._1).foldLeft(Double.PositiveInfinity)(scala.benchmark.internal.StatisticsNumbers.minimum)

  /** Sorted once: reports query several percentiles of the same statistics. */
  private lazy val population = (if sampled then values else values.filterNot(_._1.isNaN)).sortBy(_._1)
  private lazy val populationCount = population.map(_._2).sum

  def percentile(percent: Double): Double =
    require(percent >= 0 && percent <= 100)
    if count == 0 then Double.NaN
    else
      if percent == 0 then return minimum
      val n = populationCount
      if n == 0 then return Double.NaN
      val sorted = population
      def at(rank: Long): Double =
        var accumulated = 0L
        sorted.find { case (_, weight) => accumulated += weight; accumulated >= rank }.getOrElse(sorted.last)._1
      val position = percent * (n + 1) / 100
      if !sampled && position < 1 then sorted.head._1
      else if !sampled && position >= n then sorted.last._1
      else
        val lower = math.floor(position).toLong
        val first = at(lower)
        first + (position - lower) * (at(lower + 1) - first)

end Statistics
