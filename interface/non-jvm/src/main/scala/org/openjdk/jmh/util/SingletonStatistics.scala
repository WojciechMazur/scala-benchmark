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

class SingletonStatistics(private val value: Double) extends AbstractStatistics {
  def getN(): Long = 1L
  def getSum(): Double = value
  def getMin(): Double = value
  def getMax(): Double = value
  def getVariance(): Double = Double.NaN
  def getPercentile(rank: Double): Double = value
  override def getHistogram(levels: Array[Double]): Array[Int] = {
    val result = new Array[Int](levels.length - 1)
    var index = 0
    var found = false
    while (index < result.length && !found) {
      if (levels(index) <= value && value < levels(index + 1)) { result(index) = 1; found = true }
      index += 1
    }
    result
  }
  def getRawData(): java.util.Iterator[java.util.Map.Entry[java.lang.Double, java.lang.Long]] =
    new java.util.Iterator[java.util.Map.Entry[java.lang.Double, java.lang.Long]] {
      private var returned = false
      def hasNext(): Boolean = !returned
      def next(): java.util.Map.Entry[java.lang.Double, java.lang.Long] = {
        if (returned) throw new java.util.NoSuchElementException("No more elements.")
        returned = true
        new java.util.AbstractMap.SimpleImmutableEntry[java.lang.Double, java.lang.Long](value, 1L)
      }
      override def remove(): Unit = throw new UnsupportedOperationException("Element cannot be removed.")
    }
}
