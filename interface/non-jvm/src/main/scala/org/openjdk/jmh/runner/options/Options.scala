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
package org.openjdk.jmh.runner.options

import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.results.format.ResultFormatType
import org.openjdk.jmh.util.Optional

trait Options extends Serializable:
  def getWarmupIterations(): Optional[java.lang.Integer]
  def getMeasurementIterations(): Optional[java.lang.Integer]
  def getWarmupBatchSize(): Optional[java.lang.Integer]
  def getMeasurementBatchSize(): Optional[java.lang.Integer]
  def getForkCount(): Optional[java.lang.Integer]
  def getWarmupForkCount(): Optional[java.lang.Integer]
  def getThreads(): Optional[java.lang.Integer]
  def getOperationsPerInvocation(): Optional[java.lang.Integer]
  def getWarmupTime(): Optional[TimeValue]
  def getMeasurementTime(): Optional[TimeValue]
  def getTimeout(): Optional[TimeValue]
  def getTimeUnit(): Optional[TimeUnit]
  def shouldDoGC(): Optional[java.lang.Boolean]
  def shouldSyncIterations(): Optional[java.lang.Boolean]
  def shouldFailOnError(): Optional[java.lang.Boolean]
  def verbosity(): Optional[VerboseMode]
  def getWarmupMode(): Optional[WarmupMode]
  def getOutput(): Optional[String]
  def getResult(): Optional[String]
  def getResultFormat(): Optional[ResultFormatType]
  def getJvm(): Optional[String]
  def getIncludes(): java.util.List[String]
  def getExcludes(): java.util.List[String]
  def getWarmupIncludes(): java.util.List[String]
  def getBenchModes(): java.util.Collection[Mode]
  def getParameter(name: String): Optional[java.util.Collection[String]]
  def getThreadGroups(): Optional[Array[Int]]
  def getJvmArgs(): Optional[java.util.Collection[String]]
  def getJvmArgsAppend(): Optional[java.util.Collection[String]]
  def getJvmArgsPrepend(): Optional[java.util.Collection[String]]

end Options

trait ChainedOptionsBuilder:
  def build(): Options
  def parent(options: Options): ChainedOptionsBuilder
  def include(regex: String): ChainedOptionsBuilder
  def exclude(regex: String): ChainedOptionsBuilder
  def includeWarmup(regex: String): ChainedOptionsBuilder
  def mode(mode: Mode): ChainedOptionsBuilder
  def param(name: String, values: String*): ChainedOptionsBuilder
  def threadGroups(groups: Int*): ChainedOptionsBuilder
  def jvmArgs(arguments: String*): ChainedOptionsBuilder
  def jvmArgsAppend(arguments: String*): ChainedOptionsBuilder
  def jvmArgsPrepend(arguments: String*): ChainedOptionsBuilder
  def detectJvmArgs(): ChainedOptionsBuilder
  def warmupIterations(value: Int): ChainedOptionsBuilder
  def measurementIterations(value: Int): ChainedOptionsBuilder
  def warmupBatchSize(value: Int): ChainedOptionsBuilder
  def measurementBatchSize(value: Int): ChainedOptionsBuilder
  def forks(value: Int): ChainedOptionsBuilder
  def warmupForks(value: Int): ChainedOptionsBuilder
  def threads(value: Int): ChainedOptionsBuilder
  def operationsPerInvocation(value: Int): ChainedOptionsBuilder
  def warmupTime(value: TimeValue): ChainedOptionsBuilder
  def measurementTime(value: TimeValue): ChainedOptionsBuilder
  def timeout(value: TimeValue): ChainedOptionsBuilder
  def timeUnit(value: TimeUnit): ChainedOptionsBuilder
  def shouldDoGC(value: Boolean): ChainedOptionsBuilder
  def syncIterations(value: Boolean): ChainedOptionsBuilder
  def shouldFailOnError(value: Boolean): ChainedOptionsBuilder
  def verbosity(value: VerboseMode): ChainedOptionsBuilder
  def warmupMode(value: WarmupMode): ChainedOptionsBuilder
  def output(value: String): ChainedOptionsBuilder
  def result(value: String): ChainedOptionsBuilder
  def resultFormat(value: ResultFormatType): ChainedOptionsBuilder
  def jvm(value: String): ChainedOptionsBuilder

end ChainedOptionsBuilder
