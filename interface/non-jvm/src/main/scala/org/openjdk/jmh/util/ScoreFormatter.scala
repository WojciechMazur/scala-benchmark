/*
 * Copyright (c) 2005, 2013, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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

class ScoreFormatter { ScoreFormatter.initialize() }
object ScoreFormatter {
  private def initialize(): Unit = ()
  private val precision = IntegerProperties.read("jmh.scorePrecision", 3)
  private val ulp = 1.0 / math.pow(10, precision)
  private val threshold = ulp / 2
  def isApproximate(score: Double): Boolean = score < threshold
  private def approximate(score: Double): String = {
    val power = math.round(math.log10(score)).toInt
    "≈ " + (if (power == 0) "0" else "10" + superscript(power.toString))
  }
  private def fixed(prefix: String, score: Double): String =
    String.format("%" + prefix + "." + precision + "f", java.lang.Double.valueOf(score))
  def format(score: Double): String = if (isApproximate(score)) approximate(score) else fixed("", score)
  def format(width: Int, score: Double): String =
    if (isApproximate(score)) String.format("%" + width + "s", approximate(score)) else fixed(width.toString, score)
  def formatExact(width: Int, score: Double): String = fixed(width.toString, score)
  def formatError(error: Double): String = fixed("", math.max(error, ulp))
  def formatError(width: Int, error: Double): String = fixed(width.toString, math.max(error, ulp))
  def formatLatex(score: Double): String = if (isApproximate(score)) {
    val power = math.round(math.log10(score)).toInt
    "$\\approx " + (if (power == 0) "0" else "10^{" + power + "}") + "$"
  } else fixed("", score)
  def superscript(text: String): String = text.map {
    case '-' => '⁻'
    case '0' => '⁰'
    case '1' => '¹'
    case '2' => '²'
    case '3' => '³'
    case '4' => '⁴'
    case '5' => '⁵'
    case '6' => '⁶'
    case '7' => '⁷'
    case '8' => '⁸'
    case '9' => '⁹'
    case c   => c
  }
}

private[jmh] object IntegerProperties {
  def read(name: String, fallback: Int): Int = {
    val text = System.getProperty(name)
    if (text == null) return fallback
    try {
      var index = 0
      var negative = false
      if (text.nonEmpty && (text(0) == '-' || text(0) == '+')) { negative = text(0) == '-'; index = 1 }
      val radix = if (text.startsWith("0x", index) || text.startsWith("0X", index)) { index += 2; 16 }
      else if (text.startsWith("#", index)) { index += 1; 16 }
      else if (text.startsWith("0", index) && text.length > index + 1) { index += 1; 8 }
      else 10
      val digits = text.substring(index)
      if (digits.startsWith("-") || digits.startsWith("+")) throw new NumberFormatException("Invalid sign")
      java.lang.Integer.parseInt((if (negative) "-" else "") + digits, radix)
    } catch { case _: NumberFormatException => fallback }
  }
}
