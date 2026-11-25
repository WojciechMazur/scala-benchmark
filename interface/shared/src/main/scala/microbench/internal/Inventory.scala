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

import scala.quoted.*

import microbench.Platform

/** Compile-time platform eligibility of the benchmarks declared by one class. */
object Inventory:

  /** One entry per benchmark or benchmark group of `T`. Expands to constants only: it never constructs `T`. */
  inline def of[T]: Array[InventoryEntry] = ${ entries[T] }

  private def entries[T: Type](using Quotes): Expr[Array[InventoryEntry]] =
    val symbols = new BenchmarkSymbols
    import symbols.*
    import symbols.quotes.reflect.*
    val cls = TypeRepr.of[T].typeSymbol
    validatePlatformTargets(cls)
    val generated = benchmarks(cls).groupBy(groupName).toList.sortBy(_._1).map { case (group, members) =>
      val restriction = groupRestriction(cls, members)
      val platforms = Expr.ofSeq(restriction.allowed.map(name => '{ Platform.valueOf(${ Expr(name) }) }))
      '{
        InventoryEntry(
          ${ Expr(cls.fullName + "." + group) },
          Eligibility($platforms.toVector, ${ Expr(restriction.reasons) }.toVector)
        )
      }
    }
    '{ Array(${ Varargs(generated) }*) }
