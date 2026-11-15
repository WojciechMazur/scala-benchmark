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
package org.openjdk.jmh.runner.options

import java.util.concurrent.TimeUnit

class TimeValue(private val time: Long, private val unit: TimeUnit) extends Serializable:
  require(time >= 0, s"Time should be greater or equal to zero: $time")
  def getTime(): Long = time
  def getTimeUnit(): TimeUnit = unit
  def convertTo(target: TimeUnit): Long = target.convert(time, unit)
  def sleep(): Unit = scala.benchmark.internal.TimeServices.sleep(time, unit)

  override def equals(other: Any): Boolean = other match
    case that: TimeValue => getClass == that.getClass && time == that.time && unit == that.unit
    case _               => false

  override def hashCode(): Int = 41 * (41 * 3 + (time ^ (time >>> 32)).toInt) + Option(unit).fold(0)(_.hashCode())
  override def toString(): String = if time == 0 then "single-shot" else s"$time ${TimeValue.tuToString(unit)}"

object TimeValue:
  val NONE = new TimeValue(0, TimeUnit.SECONDS)
  def days(value: Long): TimeValue = new TimeValue(value, TimeUnit.DAYS)
  def hours(value: Long): TimeValue = new TimeValue(value, TimeUnit.HOURS)
  def minutes(value: Long): TimeValue = new TimeValue(value, TimeUnit.MINUTES)
  def seconds(value: Long): TimeValue = new TimeValue(value, TimeUnit.SECONDS)
  def milliseconds(value: Long): TimeValue = new TimeValue(value, TimeUnit.MILLISECONDS)
  def microseconds(value: Long): TimeValue = new TimeValue(value, TimeUnit.MICROSECONDS)
  def nanoseconds(value: Long): TimeValue = new TimeValue(value, TimeUnit.NANOSECONDS)

  def tuToString(unit: TimeUnit): String = unit match
    case TimeUnit.DAYS         => "day"
    case TimeUnit.HOURS        => "hr"
    case TimeUnit.MINUTES      => "min"
    case TimeUnit.SECONDS      => "s"
    case TimeUnit.MILLISECONDS => "ms"
    case TimeUnit.MICROSECONDS => "us"
    case TimeUnit.NANOSECONDS  => "ns"
    case null                  => throw new NullPointerException("timeUnit")

  def valueOf(value: String): TimeValue = fromString(value)

  def fromString(value: String): TimeValue =
    require(value != null, "String is null")
    val normalized = scala.benchmark.internal.Ascii.lower(value.replace(" ", ""))
    val units = Vector(
      "ns" -> TimeUnit.NANOSECONDS,
      "ms" -> TimeUnit.MILLISECONDS,
      "us" -> TimeUnit.MICROSECONDS,
      "s" -> TimeUnit.SECONDS,
      "m" -> TimeUnit.MINUTES,
      "hr" -> TimeUnit.HOURS,
      "day" -> TimeUnit.DAYS
    )
    units.find(p => normalized.contains(p._1)) match
      case Some((suffix, unit)) => new TimeValue(normalized.take(normalized.indexOf(suffix)).toInt.toLong, unit)
      case None                 => new TimeValue(normalized.toInt.toLong, TimeUnit.SECONDS)

end TimeValue
