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

import org.openjdk.jmh.infra.{BenchmarkParams, IterationParams}
import org.openjdk.jmh.results.{BenchmarkResult, IterationResult, RunResult}

trait OutputFormat:
  def iteration(params: BenchmarkParams, iteration: IterationParams, index: Int): Unit
  def iterationResult(params: BenchmarkParams, iteration: IterationParams, index: Int, result: IterationResult): Unit
  def startBenchmark(params: BenchmarkParams): Unit
  def endBenchmark(result: BenchmarkResult): Unit
  def startRun(): Unit
  def endRun(results: java.util.Collection[RunResult]): Unit
  def print(text: String): Unit
  def println(text: String): Unit
  def verbosePrintln(text: String): Unit
  def write(value: Int): Unit
  def write(bytes: Array[Byte]): Unit
  def flush(): Unit
  def close(): Unit
