/*
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

/** State storage and fixture arbitration are deliberately separate from user state.
  */
final class StateSlot(val instance: AnyRef, val shared: Boolean):
  @volatile private var failure: Throwable = null
  private val ready = Array(false, false, false)

  private val locks =
    Array.fill(3)(new java.util.concurrent.locks.ReentrantLock)

  def enter(level: Int, setup: Boolean): Boolean =
    if !shared then true
    else
      locks(level).lockInterruptibly()
      if failure != null then
        locks(level).unlock(); throw failure
      if ready(level) != setup then true
      else
        locks(level).unlock(); false

  def fail(error: Throwable): Unit = if shared then failure = error

  def leave(level: Int, setup: Boolean, success: Boolean): Unit =
    if shared then
      if success then ready(level) = setup
      locks(level).unlock()
