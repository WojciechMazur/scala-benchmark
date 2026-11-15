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

/** Fixture arbitration needs no locks when a single event loop owns all state. */
final class StateSlot(val instance: AnyRef, val shared: Boolean):
  private var failure: Throwable = null
  private val ready = Array(false, false, false)

  def enter(level: Int, setup: Boolean): Boolean =
    if failure != null then throw failure
    !shared || ready(level) != setup

  def fail(error: Throwable): Unit = if shared then failure = error

  def leave(level: Int, setup: Boolean, success: Boolean): Unit =
    if shared && success then ready(level) = setup
