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
package scala.scalanative.jmh

/** JMH 1.37 SampleBuffer's 10-bit quantized histogram and halving semantics. */
final class SampleBuffer {
  private val buckets = new Array[Array[Long]](54)
  def add(value: Long): Unit = {
    require(value >= 0, "Negative clock interval")
    val bucket = math.max(0, 54 - java.lang.Long.numberOfLeadingZeros(value))
    if (buckets(bucket) == null) buckets(bucket) = new Array[Long](1024)
    buckets(bucket)((value >>> bucket).toInt) += 1
  }
  def half(): Unit = buckets.foreach { bucket =>
    if (bucket != null) {
      var i = 0
      while (i < bucket.length) {
        val n = bucket(i) / 2
        if (n != 0) bucket(i) = n
        i += 1
      }
    }
  }
  def entries: Vector[(Long, Long)] = buckets.iterator.zipWithIndex.flatMap {
    case (bucket, shift) =>
      if (bucket == null) Iterator.empty
      else
        bucket.iterator.zipWithIndex.collect {
          case (count, n) if count != 0 => (n.toLong << shift, count)
        }
  }.toVector
  def merge(other: SampleBuffer): Unit = other.entries.foreach { case (value, count) =>
    val bucket = math.max(0, 54 - java.lang.Long.numberOfLeadingZeros(value))
    if (buckets(bucket) == null) buckets(bucket) = new Array[Long](1024)
    buckets(bucket)((value >>> bucket).toInt) += count
  }
  def addCount(value: Long, count: Long): Unit = {
    require(count > 0)
    add(value)
    val bucket = math.max(0, 54 - java.lang.Long.numberOfLeadingZeros(value))
    buckets(bucket)((value >>> bucket).toInt) += count - 1
  }
}

final case class Statistics(values: Vector[(Double, Long)]) {
  val count: Long = values.map(_._2).sum
  val mean: Double =
    if (count == 0) Double.NaN
    else values.map { case (v, n) => v * n }.sum / count
  val variance: Double =
    if (count <= 1) Double.NaN
    else {
      val sum = values.map { case (v, n) => (v - mean) * (v - mean) * n }.sum
      val correction = values.map { case (v, n) => (v - mean) * n }.sum
      (sum - correction * correction / count) / (count - 1)
    }
  val deviation: Double = math.sqrt(variance)
  def error: Double = if (count <= 2) Double.NaN
  else
    StudentT.quantile(0.9995, count - 1) * deviation / math.sqrt(count.toDouble)
  def percentile(percent: Double): Double = {
    require(percent >= 0 && percent <= 100)
    if (count == 0) Double.NaN
    else {
      val sorted = values.sortBy(_._1)
      def at(rank: Long): Double = {
        var n = 0L
        sorted
          .find { case (_, weight) => n += weight; n >= rank }
          .getOrElse(sorted.last)
          ._1
      }
      val position = percent * (count + 1) / 100
      if (position < 1) sorted.head._1
      else if (position >= count) sorted.last._1
      else {
        val lower = position.toLong
        val a = at(lower)
        a + (position - lower) * (at(lower + 1) - a)
      }
    }
  }
}

/** Inverse Student-t via regularized incomplete beta and bracketed bisection. Uses Lanczos
  * log-gamma and the modified Lentz continued fraction. These routines are tested against the
  * Commons Math distribution used by JMH.
  */
private[jmh] object StudentT {
  private val coefficients = Array(676.5203681218851, -1259.1392167224028, 771.3234287776531,
    -176.6150291621406, 12.507343278686905, -0.13857109526572012, 9.984369578019572e-6,
    1.5056327351493116e-7)
  private def logGamma(value: Double): Double = {
    val z = value - 1
    var x = 0.99999999999980993
    var i = 0
    while (i < coefficients.length) {
      x += coefficients(i) / (z + i + 1); i += 1
    }
    val t = z + 7.5
    0.9189385332046727 + (z + 0.5) * math.log(t) - t + math.log(x)
  }
  private def fraction(a: Double, b: Double, x: Double): Double = {
    val tiny = 1e-300
    def bound(v: Double): Double = if (math.abs(v) < tiny) tiny else v
    var c = 1.0
    var d = 1.0 / bound(1 - (a + b) * x / (a + 1))
    var result = d
    var m = 1
    var converged = false
    while (m <= 10000 && !converged) {
      val aa = m * (b - m) * x / ((a + 2 * m - 1) * (a + 2 * m))
      d = 1 / bound(1 + aa * d); c = bound(1 + aa / c); result *= d * c
      val ab = -(a + m) * (a + b + m) * x / ((a + 2 * m) * (a + 2 * m + 1))
      d = 1 / bound(1 + ab * d); c = bound(1 + ab / c)
      val delta = d * c
      result *= delta
      converged = math.abs(delta - 1) < 3e-14
      m += 1
    }
    require(converged, "Student-t continued fraction failed to converge")
    result
  }
  private def beta(x: Double, a: Double, b: Double): Double = {
    if (x <= 0) 0
    else if (x >= 1) 1
    else {
      // Avoid subtracting large, nearly equal log-gamma values for beta(a, 1/2).
      val large = if (a == 0.5) b else a
      val logBeta = if ((a == 0.5 || b == 0.5) && large >= 32) {
        val inverse = 1.0 / large
        0.5723649429247001 - 0.5 * math.log(large) + inverse / 8 -
          math.pow(inverse, 3) / 192 + math.pow(inverse, 5) / 640
      } else logGamma(a) + logGamma(b) - logGamma(a + b)
      val factor = math.exp(-logBeta + a * math.log(x) + b * math.log1p(-x))
      if (x < (a + 1) / (a + b + 2)) factor * fraction(a, b, x) / a
      else 1 - factor * fraction(b, a, 1 - x) / b
    }
  }
  def quantile(probability: Double, degrees: Long): Double = {
    require(probability > 0.5 && probability < 1 && degrees > 0)
    val df = degrees.toDouble
    def cdf(t: Double): Double = 1 - 0.5 * beta(df / (df + t * t), df / 2, 0.5)
    var low = 0.0
    var high = 1.0
    while (cdf(high) < probability) high *= 2
    var i = 0
    while (i < 100) {
      val middle = (low + high) / 2
      if (cdf(middle) < probability) low = middle else high = middle
      i += 1
    }
    (low + high) / 2
  }
}
