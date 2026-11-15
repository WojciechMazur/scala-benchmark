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

/** Sequenced, complete progress records; time is measured only by the supervising process. */
private[runtime] object ForkProgress:
  val startupNanos: Long = 30000000000L

  def budget(duration: Long, timeout: Long): Long =
    require(duration >= 0 && timeout >= 0, "Negative execution budget")
    if duration > Long.MaxValue - timeout then Long.MaxValue else math.max(1L, duration + timeout)

  def message(sequence: Long, budgetNanos: Long): String =
    require(sequence >= 0 && budgetNanos > 0, "Invalid fork progress")
    s"3 $sequence $budgetNanos\n"

  def parse(text: String): Option[(Long, Long)] =
    if !text.endsWith("\n") then None
    else
      val parts = text.dropRight(1).split(" ", -1)
      def digits(value: String): Boolean = value.nonEmpty && value.forall(c => c >= '0' && c <= '9')
      if parts.length != 3 || parts(0) != "3" || !digits(parts(1)) || !digits(parts(2)) then None
      else
        try
          val sequence = parts(1).toLong
          val budget = parts(2).toLong
          if budget > 0 then Some(sequence -> budget) else None
        catch case _: NumberFormatException => None

final private[runtime] class ForkDeadline(startNanos: Long, initialBudgetNanos: Long):
  require(initialBudgetNanos > 0, "Invalid startup budget")
  private var startedAt = startNanos
  private var budget = initialBudgetNanos
  private var sequence = -1L

  def receive(text: String, nowNanos: Long): Boolean = ForkProgress.parse(text) match
    case Some((next, duration)) if next > sequence =>
      sequence = next; startedAt = nowNanos; budget = duration; true
    case _ => false

  def expired(nowNanos: Long): Boolean = nowNanos - startedAt >= budget
