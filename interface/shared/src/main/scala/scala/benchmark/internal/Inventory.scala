package scala.benchmark.internal

import scala.benchmark.Platform
import scala.quoted.*

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
