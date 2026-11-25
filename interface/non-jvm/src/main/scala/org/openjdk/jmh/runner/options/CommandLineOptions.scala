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

import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.results.format.ResultFormatType

class CommandLineOptions(arguments: String*) extends OptionsBuilder:
  private var help = false
  private var list = false
  private var listParams = false
  private var formats = false
  private val scalar = scala.collection.mutable.Set.empty[String]

  try
    var index = 0
    while index < arguments.size do
      val key = arguments(index)
      def argument(): String =
        index += 1
        require(index < arguments.size, "Missing argument for " + key)
        arguments(index)
      if key.startsWith("-") && !Set("-e", "-p", "-bm", "-wmb", "-l", "-lp", "-lrf", "-h")(key) then
        require(scalar.add(key), "Repeated option " + key)
      def mode(value: String): Mode = Mode.deepValueOf(value)
      key match
        case "-h" | "--help" => help = true
        case "-l"            => list = true
        case "-lp"           => listParams = true
        case "-lrf"          => formats = true
        case "-e"            => argument().split(",").foreach(exclude)
        case "-bm"           => argument().split(",").foreach(value => this.mode(mode(value)))
        case "-wmb"          => argument().split(",").foreach(includeWarmup)
        case "-wm"           => warmupMode(WarmupMode.valueOf(argument()))
        case "-p"            =>
          val pair = argument().split("=", 2)
          require(pair.length == 2, "Expected -p name=value[,value]")
          param(pair(0), pair(1).split(",", -1)*)
        case "-wi"  => warmupIterations(argument().toInt)
        case "-i"   => measurementIterations(argument().toInt)
        case "-w"   => warmupTime(TimeValue.fromString(argument()))
        case "-r"   => measurementTime(TimeValue.fromString(argument()))
        case "-wbs" => warmupBatchSize(argument().toInt)
        case "-bs"  => measurementBatchSize(argument().toInt)
        case "-f"   => forks(argument().toInt)
        case "-wf"  => warmupForks(argument().toInt)
        case "-t"   =>
          val value = argument();
          threads(
            if value.equalsIgnoreCase("max") then -1
            else
              require(value.toInt > 0, "Thread count must be positive or max"); value.toInt
          )
        case "-tg"  => threadGroups(argument().split(",").map(_.toInt)*)
        case "-opi" => operationsPerInvocation(argument().toInt)
        case "-si"  => syncIterations(java.lang.Boolean.parseBoolean(argument()))
        case "-gc"  => shouldDoGC(java.lang.Boolean.parseBoolean(argument()))
        case "-foe" => shouldFailOnError(java.lang.Boolean.parseBoolean(argument()))
        case "-v"   => verbosity(VerboseMode.valueOf(argument()))
        case "-o"   => output(argument())
        case "-rf"  => resultFormat(ResultFormatType.valueOf(microbench.internal.Ascii.upper(argument())))
        case "-rff" => result(argument())
        case "-to"  => timeout(TimeValue.fromString(argument()))
        case "-tu"  =>
          val units = Map(
            "ns" -> "NANOSECONDS",
            "us" -> "MICROSECONDS",
            "ms" -> "MILLISECONDS",
            "s" -> "SECONDS",
            "m" -> "MINUTES",
            "h" -> "HOURS"
          )
          val value = microbench.internal.Ascii.lower(argument())
          require(units.contains(value), "Unknown time unit: " + value)
          timeUnit(java.util.concurrent.TimeUnit.valueOf(units(value)))
        case "-jvm"                         => jvm(argument())
        case "-jvmArgs"                     => jvmArgs(argument().split(" +")*)
        case "-jvmArgsAppend"               => jvmArgsAppend(argument().split(" +")*)
        case "-jvmArgsPrepend"              => jvmArgsPrepend(argument().split(" +")*)
        case value if value.startsWith("-") =>
          throw new IllegalArgumentException("Unsupported benchmark option: " + value)
        case value => include(value)
      end match
      index += 1
    end while
  catch case error: Exception => throw new CommandLineOptionException(error.getMessage, error)

  end try

  def shouldHelp(): Boolean = help
  def shouldList(): Boolean = list
  def shouldListWithParams(): Boolean = listParams
  def shouldListResultFormats(): Boolean = formats
  def shouldListProfilers(): Boolean = false
  def showHelp(): Unit = println("JMH-compatible benchmark options: -l -lp -lrf -bm -wi -i -w -r -f -t -p -rf -rff")
  def listResultFormats(): Unit = println(ResultFormatType.values.mkString(", "))

end CommandLineOptions
