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
package scala.benchmark.runtime

/** Resolution and cost of the monotonic clock, measured once per run and reported with the results. */
final case class ClockCalibration(
    minimumDeltaNanos: Long,
    averageCallNanos: Double
):
  def available: Boolean = minimumDeltaNanos > 0

object ClockCalibration:
  val Unavailable: ClockCalibration = ClockCalibration(0, Double.NaN)

  def measure(): ClockCalibration =
    var previous = System.nanoTime()
    val start = previous
    var minimum = Long.MaxValue
    var i = 0
    while i < 10000 do
      val now = System.nanoTime()
      require(now >= previous && now != 0, "Monotonic clock is unavailable")
      val delta = now - previous
      if delta > 0 then minimum = math.min(minimum, delta)
      previous = now
      i += 1
    require(minimum != Long.MaxValue, "Clock has no observable resolution")
    ClockCalibration(minimum, (previous - start) / 10000.0)
