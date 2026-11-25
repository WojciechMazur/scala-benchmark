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
package microbench.internal

/** JMH 1.37 SampleBuffer's 10-bit quantized histogram and halving semantics. */
class SamplingHistogram:
  private val buckets = new Array[Array[Long]](54)

  def add(value: Long): Unit =
    require(value >= 0, "Negative clock interval")
    val bucket = math.max(0, 54 - java.lang.Long.numberOfLeadingZeros(value))
    if buckets(bucket) == null then buckets(bucket) = new Array[Long](1024)
    buckets(bucket)((value >>> bucket).toInt) += 1

  def half(): Unit = buckets.foreach { bucket =>
    if bucket != null then
      var i = 0
      while i < bucket.length do
        val n = bucket(i) / 2
        if n != 0 then bucket(i) = n
        i += 1
  }

  def entries: Vector[(Long, Long)] = buckets.iterator.zipWithIndex.flatMap { case (bucket, shift) =>
    if bucket == null then Iterator.empty
    else
      bucket.iterator.zipWithIndex.collect {
        case (count, n) if count != 0 => (n.toLong << shift, count)
      }
  }.toVector

  def merge(other: SamplingHistogram): Unit = other.entries.foreach { case (value, count) =>
    val bucket = math.max(0, 54 - java.lang.Long.numberOfLeadingZeros(value))
    if buckets(bucket) == null then buckets(bucket) = new Array[Long](1024)
    buckets(bucket)((value >>> bucket).toInt) += count
  }

  def addCount(value: Long, count: Long): Unit =
    require(count > 0)
    add(value)
    val bucket = math.max(0, 54 - java.lang.Long.numberOfLeadingZeros(value))
    buckets(bucket)((value >>> bucket).toInt) += count - 1

end SamplingHistogram
