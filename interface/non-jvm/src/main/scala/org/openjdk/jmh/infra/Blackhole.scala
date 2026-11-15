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
package org.openjdk.jmh.infra

import scala.benchmark.internal.BlackholeSink

/** Source-compatible consumption API. The Native backend is an observable C compiler barrier, rather than HotSpot's
  * compiler blackhole intrinsic.
  */
final class Blackhole(challengeResponse: String):

  require(
    challengeResponse == Blackhole.Challenge,
    "Blackholes must be injected by the harness"
  )

  def consume(value: Boolean): Unit = BlackholeSink.consume(if value then 1L else 0L)
  def consume(value: Byte): Unit = BlackholeSink.consume(value.toLong)
  def consume(value: Short): Unit = BlackholeSink.consume(value.toLong)
  def consume(value: Char): Unit = BlackholeSink.consume(value.toLong)
  def consume(value: Int): Unit = BlackholeSink.consume(value.toLong)
  def consume(value: Long): Unit = BlackholeSink.consume(value)

  def consume(value: Float): Unit =
    BlackholeSink.consume(java.lang.Float.floatToRawIntBits(value).toLong)

  def consume(value: Double): Unit =
    BlackholeSink.consume(java.lang.Double.doubleToRawLongBits(value))

  def consume(value: AnyRef): Unit = BlackholeSink.consumeReference(value)

  def evaporate(challengeResponse: String): Unit =
    require(challengeResponse == Blackhole.Challenge)

object Blackhole:

  final val Challenge =
    "Today's password is swordfish. I understand instantiating Blackholes directly is dangerous."

  def consumeCPU(tokens: Long): Unit =
    var value = 0x123456789abcdefL
    var i = 0L
    while i < tokens do
      value = (value * 0x5deece66dL + 0xbL) ^ (value >>> 17)
      i += 1
    BlackholeSink.consume(value)
