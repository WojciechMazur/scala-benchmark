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
package microbench.internal

/** Platform eligibility of the project's benchmarks, generated at compile time by the sbt plugin. */
object JvmInventory:

  /** Empty when the project has no generated inventory, for example a pure Java benchmark project. */
  def load(loader: ClassLoader): Vector[InventoryEntry] =
    try
      Class
        .forName(InventoryProvider.GeneratedName + "$", true, loader)
        .getField("MODULE$")
        .get(null)
        .asInstanceOf[InventoryProvider]
        .entries
        .toVector
    catch case _: ClassNotFoundException => Vector.empty
