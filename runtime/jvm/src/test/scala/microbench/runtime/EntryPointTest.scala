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

import org.junit.Assert.*
import org.junit.Test

/** The public `microbench.run(args)(suite[...])` entry point, used without a build tool plugin. */
class EntryPointTest:

  private val benchmark = """package bench
    |import org.openjdk.jmh.annotations._
    |class Example { @Benchmark def run(): Int = 1 }
    |""".stripMargin

  private def compiles(usage: String)(check: (Boolean, java.nio.file.Path) => Unit): Unit =
    HarnessCompiler.compile(benchmark + usage, derive = Nil)(check)

  @Test def suiteIsNamedByItsClass(): Unit =
    compiles("object Main { val suites = Seq(microbench.suite[Example]) }") { (success, output) =>
      assertTrue(success)
      HarnessCompiler.withLoader(output) { loader =>
        val module = loader.loadClass("bench.Main$").getField("MODULE$").get(null)
        val suites =
          module.getClass.getMethod("suites").invoke(module).asInstanceOf[Seq[microbench.BenchmarkSuite]]
        val entries = Registry.entries(suites)
        assertEquals(Seq("bench.Example"), entries.map(_._1))
        assertEquals("bench.Example.run", entries.head._2.metadata().head.name)
      }
    }

  @Test def suiteRejectsObjects(): Unit =
    compiles("object Bench\nobject Main { val s = microbench.suite[Bench.type] }")((success, _) =>
      assertFalse(success)
    )

  @Test def suiteRejectsNestedClasses(): Unit =
    compiles(
      "object Outer { class Nested { @Benchmark def run(): Int = 1 } }\nobject Main { val s = microbench.suite[Outer.Nested] }"
    )((success, _) => assertFalse(success))

  @Test def suiteRejectsTheDefaultPackage(): Unit =
    HarnessCompiler.compile(
      """import org.openjdk.jmh.annotations._
        |class Example { @Benchmark def run(): Int = 1 }
        |object Main { val s = microbench.suite[Example] }
        |""".stripMargin,
      derive = Nil
    )((success, _) => assertFalse(success))

  @Test def duplicateSuitesAreRejected(): Unit =
    val suite = new Suite:
      def metadata(): Array[BenchmarkMetadata] = Array.empty
      def instantiate(state: Int): AnyRef = null
      def assign(state: Int, target: AnyRef, parameter: String, value: String): Unit = ()
      def setup(state: Int, level: Int, target: AnyRef, environment: Environment): Unit = ()
      def teardown(state: Int, level: Int, target: AnyRef, environment: Environment): Unit = ()
      def measure(benchmark: Int, environment: Environment, control: IterationControl): RawResult = null
    val error = assertThrows(
      classOf[IllegalArgumentException],
      () => Registry.install(Seq("bench.A" -> suite, "bench.A" -> suite), Nil)
    )
    assertTrue(error.getMessage.contains("bench.A"))

end EntryPointTest
