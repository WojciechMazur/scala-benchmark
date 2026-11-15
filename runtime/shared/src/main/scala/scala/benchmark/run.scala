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
package scala.benchmark

import scala.quoted.{quotes, Expr, Quotes, Type}

/** One benchmark class with its harness, derived at compile time by [[suite]]. */
final class BenchmarkSuite private[benchmark] (
    private[benchmark] val name: String,
    private[benchmark] val harness: runtime.Suite
)

object BenchmarkSuite:
  private[benchmark] def apply(name: String, harness: runtime.Suite): BenchmarkSuite = new BenchmarkSuite(name, harness)

/** Derives the harness of benchmark class `T`.
  *
  * {{{
  * @main def benchmarks(args: String*) = scala.benchmark.run(args)(
  *   suite[SumBenchmark],
  *   suite[ParseBenchmark]
  * )
  * }}}
  */
inline def suite[T]: BenchmarkSuite = BenchmarkSuite(benchmarkClassName[T], runtime.Harness.derive[T])

/** Runs the given benchmarks with JMH command-line options, such as `-l`, `-wi 3 -i 5 -f 2` or `-rf json`. */
def run(args: Seq[String])(suites: BenchmarkSuite*): Unit =
  runtime.Main.run(args.toArray, runtime.Registry.entries(suites), Nil)

private inline def benchmarkClassName[T]: String = ${ benchmarkClassNameImpl[T] }

private def benchmarkClassNameImpl[T: Type](using Quotes): Expr[String] =
  import quotes.reflect.*
  val tpe = TypeRepr.of[T].dealias
  val cls = tpe.classSymbol.filter(symbol => tpe =:= symbol.typeRef && !symbol.flags.is(Flags.Module))
  cls match
    case None =>
      report.errorAndAbort(s"suite[${tpe.show}]: benchmarks are classes; ${tpe.show} is not a class type")
    case Some(symbol) if !symbol.owner.isPackageDef =>
      report.errorAndAbort(s"suite[${symbol.name}]: benchmark classes must be top-level")
    case Some(symbol) if symbol.owner == defn.EmptyPackageClass || symbol.owner.fullName == "<empty>" =>
      report.errorAndAbort(s"suite[${symbol.name}]: benchmarks need a named package")
    case Some(symbol) => Expr(symbol.fullName)
