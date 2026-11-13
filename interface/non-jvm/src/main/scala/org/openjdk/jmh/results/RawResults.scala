/*
 * Copyright (c) 2005, 2013, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.jmh.results

class RawResults:
  var allOps: Double = 0.0
  var measuredOps: Double = 0.0
  var realTime: Long = 0L
  var startTime: Long = 0L
  var stopTime: Long = 0L
  def getTime(): Long = if realTime > 0 then realTime else stopTime - startTime

class BenchmarkTaskResult(private val allOperations: Long, private val measuredOperations: Long):
  private val results = new java.util.ArrayList[Result[?]]

  def add(result: Result[?]): Unit =
    results.add(result); ()

  def getResults(): java.util.Collection[Result[?]] = results
  def getAllOps(): Long = allOperations
  def getMeasuredOps(): Long = measuredOperations
