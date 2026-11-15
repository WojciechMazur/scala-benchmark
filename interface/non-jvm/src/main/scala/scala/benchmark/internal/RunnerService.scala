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
package scala.benchmark.internal

trait RunnerService extends PlatformRunnerService:

  def renderResults(
      results: java.util.Collection[org.openjdk.jmh.results.RunResult],
      format: org.openjdk.jmh.results.format.ResultFormatType
  ): String

  def writeResultsFile(
      results: java.util.Collection[org.openjdk.jmh.results.RunResult],
      format: org.openjdk.jmh.results.format.ResultFormatType,
      file: String
  ): Unit

  def run(
      options: org.openjdk.jmh.runner.options.Options,
      format: org.openjdk.jmh.runner.format.OutputFormat = null
  ): java.util.Collection[org.openjdk.jmh.results.RunResult]

  def runSingle(
      options: org.openjdk.jmh.runner.options.Options,
      format: org.openjdk.jmh.runner.format.OutputFormat = null
  ): org.openjdk.jmh.results.RunResult

  def list(
      options: org.openjdk.jmh.runner.options.Options,
      parameters: Boolean,
      format: org.openjdk.jmh.runner.format.OutputFormat = null
  ): Unit
