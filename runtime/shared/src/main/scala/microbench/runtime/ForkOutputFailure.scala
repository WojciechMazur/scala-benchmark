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
package microbench.runtime

/** A blocked output channel cannot safely carry further benchmark diagnostics. */
final private[runtime] class ForkOutputException(message: String, cause: Throwable = null)
    extends IllegalStateException(message, cause)

private[runtime] object ForkOutputFailure:
  @volatile var failed: Boolean = false

  def fail(message: String, cause: Throwable = null): ForkOutputException =
    failed = true
    new ForkOutputException(message, cause)

  def check(): Unit = if failed then throw new ForkOutputException("Benchmark output channel is unusable")
