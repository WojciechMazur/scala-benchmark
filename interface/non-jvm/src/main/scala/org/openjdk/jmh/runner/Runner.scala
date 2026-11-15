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
package org.openjdk.jmh.runner

import scala.benchmark.internal.RunnerServices

import org.openjdk.jmh.runner.options.{CommandLineOptions, Options}

class RunnerException(message: String, cause: Throwable) extends Exception(message, cause):
  def this(message: String) = this(message, null)
  def this(cause: Throwable) = this(Option(cause).fold("")(_.getMessage), cause)

class NoBenchmarksException extends RunnerException("No benchmarks match the selection")

class Runner(private val options: Options, private val format: org.openjdk.jmh.runner.format.OutputFormat):
  def this(options: Options) = this(options, null)
  require(options != null, "Options not allowed to be null.")
  def run(): java.util.Collection[org.openjdk.jmh.results.RunResult] = RunnerServices.current.run(options, format)
  def runSingle(): org.openjdk.jmh.results.RunResult = RunnerServices.current.runSingle(options, format)
  def list(): Unit = RunnerServices.current.list(options, false, format)
  def listWithParams(): Unit = RunnerServices.current.list(options, true, format)
  def listWithParams(options: CommandLineOptions): Unit = RunnerServices.current.list(options, true, format)
