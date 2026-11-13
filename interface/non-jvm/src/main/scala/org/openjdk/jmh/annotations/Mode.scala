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
package org.openjdk.jmh.annotations

enum Mode extends java.lang.Enum[Mode]:
  case Throughput, AverageTime, SampleTime, SingleShotTime, All

  def shortLabel(): String = this match
    case Throughput     => "thrpt"
    case AverageTime    => "avgt"
    case SampleTime     => "sample"
    case SingleShotTime => "ss"
    case All            => "all"

  def longLabel(): String = this match
    case Throughput     => "Throughput, ops/time"
    case AverageTime    => "Average time, time/op"
    case SampleTime     => "Sampling time"
    case SingleShotTime => "Single shot invocation time"
    case All            => "All benchmark modes"

object Mode:

  @scala.annotation.targetName("javaValues")
  def values(): Array[Mode] = Array(Throughput, AverageTime, SampleTime, SingleShotTime, All)

  def deepValueOf(name: String): Mode =
    values.find(_.name() == name).getOrElse {
      val matches = values.filter(_.shortLabel().startsWith(name))
      if matches.length == 1 then matches.head
      else throw new IllegalStateException("Unable to parse benchmark mode: " + name + "; known values: " + getKnown())
    }

  def getKnown(): java.util.List[String] =
    import scala.jdk.CollectionConverters.*
    values.toVector.map(mode => mode.name() + "/" + mode.shortLabel()).asJava
