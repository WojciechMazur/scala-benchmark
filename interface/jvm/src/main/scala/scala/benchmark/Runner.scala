/*
 * Copyright (c) 2026, Scala Benchmark contributors.
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
package scala.benchmark

import java.util.{Collection, Collections}

import scala.benchmark.internal.JvmPreflight

import org.openjdk.jmh.results.RunResult
import org.openjdk.jmh.runner.{NoBenchmarksException, Runner as JmhRunner, RunnerException}
import org.openjdk.jmh.runner.format.OutputFormat
import org.openjdk.jmh.runner.options.{CommandLineOptions, Options}

/** Platform-aware entry point that delegates JVM measurements to upstream JMH.
  *
  * Benchmarks that `@Platforms` excludes from the JVM are removed from the selection first; everything else is JMH's
  * own behaviour.
  */
class Runner(private val options: Options, private val format: OutputFormat):
  def this(options: Options) = this(options, null)
  require(options != null, "Options not allowed to be null.")

  private def delegate(resolved: Options): JmhRunner =
    if format == null then new JmhRunner(resolved) else new JmhRunner(resolved, format)

  /** The options restricted to JVM benchmarks, or `None` when nothing is left to run. */
  private def preflight(options: Options, listing: Boolean = false, parameters: Boolean = false): Option[Options] =
    try JvmPreflight.resolve(options, listing, parameters, format)
    catch
      case error: IllegalArgumentException if error.getMessage.contains("No benchmarks match the selection") =>
        throw new NoBenchmarksException
      case error: IllegalArgumentException =>
        throw new RunnerException(error.getMessage, error)

  def run(): Collection[RunResult] =
    preflight(options).fold(Collections.emptyList[RunResult]())(delegate(_).run())

  def runSingle(): RunResult =
    preflight(options) match
      case Some(resolved) => delegate(resolved).runSingle()
      case None           => throw new RunnerException("No results returned")

  def list(): Unit =
    preflight(options, listing = true)
    ()

  def listWithParams(): Unit =
    preflight(options, listing = true, parameters = true)
    ()

  def listWithParams(options: CommandLineOptions): Unit =
    preflight(options, listing = true, parameters = true)
    ()

end Runner
