/*
 * Copyright (c) 2026, Wojciech Mazur.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  The copyright holders
 * designate this particular file as subject to the "Classpath" exception
 * as provided in the LICENSE file that accompanied this code.
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
 */
package org.openjdk.jmh.infra

/** Deadline reads stay on the benchmark event loop, for both JS and Wasm. */
final class Control:
  var startMeasurement: Boolean = false
  private var stopped = false
  private[infra] var deadlineNanos: Long = 0L

  def stopMeasurement: Boolean =
    if !stopped && deadlineNanos != 0L && System.nanoTime() - deadlineNanos >= 0 then stopped = true
    stopped

  def stopMeasurement_=(value: Boolean): Unit = stopped = value

/** Internal host integration; absent from the portable benchmark-facing API. */
object ControlSupport:
  def deadline(control: Control, nanos: Long): Unit = control.deadlineNanos = nanos
