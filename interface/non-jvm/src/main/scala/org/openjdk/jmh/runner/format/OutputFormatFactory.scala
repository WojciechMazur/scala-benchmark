/*
 * Copyright (c) 2005, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.jmh.runner.format

import java.io.PrintStream
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.infra.{BenchmarkParams, IterationParams}
import org.openjdk.jmh.results.{BenchmarkResult, IterationResult, RunResult}
import org.openjdk.jmh.results.format.{ResultFormatFactory, ResultFormatType}
import org.openjdk.jmh.runner.IterationType
import org.openjdk.jmh.runner.options.VerboseMode
import scala.jdk.CollectionConverters.*

class OutputFormatFactory
object OutputFormatFactory {
  def createFormatInstance(out: PrintStream, mode: VerboseMode): OutputFormat = {
    if (mode == null) throw new NullPointerException("mode")
    mode match {
      case VerboseMode.SILENT                     => new SilentFormat(out, mode)
      case VerboseMode.NORMAL | VerboseMode.EXTRA => new TextReportFormat(out, mode)
    }
  }
}

private[format] abstract class AbstractOutputFormat(
    private[format] val out: PrintStream,
    private[format] val verbose: VerboseMode
) extends OutputFormat {
  protected def stream: PrintStream = {
    // Explicit checks also preserve exceptions in optimized JS/Wasm builds.
    if (out == null) throw new NullPointerException("out")
    out
  }
  def print(text: String): Unit = stream.print(text)
  def println(text: String): Unit = stream.println(text)
  def verbosePrintln(text: String): Unit = if (verbose == VerboseMode.EXTRA) stream.println(text)
  def write(value: Int): Unit = stream.write(value)
  def write(bytes: Array[Byte]): Unit = {
    val destination = stream
    if (bytes == null) throw new NullPointerException("bytes")
    destination.write(bytes)
  }
  def flush(): Unit = stream.flush()
  def close(): Unit = ()
}

private[format] class SilentFormat(out: PrintStream, verbose: VerboseMode) extends AbstractOutputFormat(out, verbose) {
  def startRun(): Unit = ()
  def endRun(results: java.util.Collection[RunResult]): Unit = ()
  def startBenchmark(params: BenchmarkParams): Unit = ()
  def endBenchmark(result: BenchmarkResult): Unit = ()
  def iteration(params: BenchmarkParams, iteration: IterationParams, index: Int): Unit = ()
  def iterationResult(params: BenchmarkParams, iteration: IterationParams, index: Int, result: IterationResult): Unit =
    ()
  override def print(text: String): Unit = ()
  override def println(text: String): Unit = ()
  override def verbosePrintln(text: String): Unit = ()
}

private[format] class TextReportFormat(out: PrintStream, verbose: VerboseMode)
    extends AbstractOutputFormat(out, verbose) {
  private def prefix(index: Int): String = String.format("Iteration %3d: ", java.lang.Integer.valueOf(index))
  private def plural(count: Int, word: String): String = word + (if (count > 1) "s" else "")
  def startRun(): Unit = ()
  def startBenchmark(params: BenchmarkParams): Unit = {
    if (params == null) throw new NullPointerException("params")
    val arguments = params.getJvmArgs().asScala.mkString(" ")
    println("# JMH version: " + params.getJmhVersion())
    println("# VM version: JDK " + params.getJdkVersion() + ", " + params.getVmName() + ", " + params.getVmVersion())
    // JVM support classification and compiler hints have no portable equivalent.
    println("# VM invoker: " + params.getJvm())
    println("# VM options: " + (if (arguments.trim.isEmpty) "<none>" else arguments))
    def phase(name: String, iteration: IterationParams): Unit = {
      val description =
        if (iteration.getCount() > 0)
          iteration.getCount().toString + " iterations, " + iteration.getTime() + " each" +
            (if (iteration.getBatchSize() <= 1) "" else ", " + iteration.getBatchSize() + " calls per op")
        else "<none>"
      println("# " + name + ": " + description)
    }
    phase("Warmup", params.getWarmup())
    phase("Measurement", params.getMeasurement())
    val timeout = params.getTimeout()
    val warning =
      timeout.convertTo(TimeUnit.NANOSECONDS) <= params.getMeasurement().getTime().convertTo(TimeUnit.NANOSECONDS) ||
        timeout.convertTo(TimeUnit.NANOSECONDS) <= params.getWarmup().getTime().convertTo(TimeUnit.NANOSECONDS)
    println(
      "# Timeout: " + timeout + " per iteration" + (if (warning) ", ***WARNING: The timeout might be too low!***"
                                                    else "")
    )
    print("# Threads: " + params.getThreads() + " " + plural(params.getThreads(), "thread"))
    if (!params.getThreadGroupLabels().isEmpty()) {
      val groups = params.getThreadGroups()
      val labels = params.getThreadGroupLabels().asScala.toVector
      val count = params.getThreads() / groups.sum
      val members = groups.zipWithIndex.map { case (threads, index) =>
        threads.toString + "x \"" + labels(index) + "\""
      }
      print(" (" + count + " " + plural(count, "group") + "; " + members.mkString(", ") + " in each group)")
    }
    println(
      if (params.shouldSynchIterations()) ", will synchronize iterations"
      else if (params.getMode() == Mode.SingleShotTime) ""
      else ", ***WARNING: Synchronize iterations are disabled!***"
    )
    println("# Benchmark mode: " + params.getMode().longLabel())
    println("# Benchmark: " + params.getBenchmark())
    if (!params.getParamsKeys().isEmpty())
      println(
        "# Parameters: (" + params
          .getParamsKeys()
          .asScala
          .map(key => key + " = " + params.getParam(key))
          .mkString(", ") + ")"
      )
  }
  def iteration(params: BenchmarkParams, iteration: IterationParams, index: Int): Unit = {
    if (iteration == null) throw new NullPointerException("iteration")
    val label = iteration.getType() match {
      case IterationType.WARMUP      => "# Warmup " + prefix(index)
      case IterationType.MEASUREMENT => prefix(index)
    }
    print(label)
    flush()
  }
  def iterationResult(
      params: BenchmarkParams,
      iteration: IterationParams,
      index: Int,
      result: IterationResult
  ): Unit = {
    if (result == null) throw new NullPointerException("result")
    if (iteration == null) throw new NullPointerException("iteration")
    val text = new StringBuilder(result.getPrimaryResult().toString)
    if (iteration.getType() == IterationType.MEASUREMENT) {
      val secondary = result.getSecondaryResults().asScala.toVector
      if (secondary.nonEmpty) {
        text.append("\n")
        val width = secondary.map(_._1.length).max + 1
        secondary.foreach { case (name, value) =>
          text
            .append(" " * prefix(index).length)
            .append("  ")
            .append((name + ":").padTo(width, ' '))
            .append(" ")
            .append(value.toString)
            .append("\n")
        }
      }
    }
    println(text.toString)
    flush()
  }
  def endBenchmark(result: BenchmarkResult): Unit = {
    println("")
    if (result != null) {
      def metric(label: String, value: org.openjdk.jmh.results.Result[?]): Unit = {
        val info = value.extendedInfo()
        if (info == null) throw new NullPointerException("extendedInfo")
        if (info.trim.nonEmpty) { println(label); println(info) }
      }
      val name = result.getParams().getBenchmark()
      metric("Result \"" + name + "\":", result.getPrimaryResult())
      result.getSecondaryResults().values().asScala.foreach { value =>
        metric("Secondary result \"" + name + ":" + value.getLabel() + "\":", value)
      }
      println("")
    }
  }
  def endRun(results: java.util.Collection[RunResult]): Unit =
    // The summary uses portable report presentation, without JVM compiler/profiler advice.
    ResultFormatFactory.getInstance(ResultFormatType.TEXT, stream).writeOut(results)
}
