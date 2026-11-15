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
package scala.benchmark.runtime

import org.openjdk.jmh.annotations.Mode

/** Internal interface/runtime boundary. Callback objects never cross a process boundary. */
trait RunObserver:
  def output[A](options: Options)(body: => A): A = ReportOutput(options)(body)
  def displaysResults: Boolean = false
  def diagnosticOutput: java.io.PrintStream = Console.out
  def reportResults(results: Vector[RunResult]): Vector[RunResult] = results
  def startRun(): Unit = ()
  def startBenchmark(d: Descriptor, mode: Mode, c: Configuration, p: Map[String, String]): Unit = ()
  def trial(d: Descriptor, mode: Mode, c: Configuration, p: Map[String, String]): TrialObserver = TrialObserver.Disabled

  def warmup(index: Int, d: Descriptor, mode: Mode, c: Configuration, p: Map[String, String]): TrialObserver =
    startBenchmark(d, mode, c, p)
    trial(d, mode, c, p)

  def endBenchmark(result: Option[RunResult], forked: Boolean): Unit = ()
  def endRun(results: Vector[RunResult]): Unit = ()
  def flush(): Unit = ()
  def close(): Unit = ()

object RunObserver:
  object Disabled extends RunObserver
