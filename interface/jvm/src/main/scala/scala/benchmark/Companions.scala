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

/** Java static modules need explicit Scala terms; portable backends export the original Scala companions. */
object TimeValue:

  export org.openjdk.jmh.runner.options.TimeValue.{
    days,
    fromString,
    hours,
    microseconds,
    milliseconds,
    minutes,
    nanoseconds,
    seconds,
    tuToString,
    valueOf,
    NONE
  }

object VerboseMode:
  export org.openjdk.jmh.runner.options.VerboseMode.{valueOf, values, EXTRA, NORMAL, SILENT}

object WarmupMode:
  export org.openjdk.jmh.runner.options.WarmupMode.{valueOf, values, BULK, BULK_INDI, INDI}

object ResultFormatType:
  export org.openjdk.jmh.results.format.ResultFormatType.{valueOf, values, CSV, JSON, LATEX, SCSV, TEXT}

object RunResult:
  export org.openjdk.jmh.results.RunResult.DEFAULT_SORT_COMPARATOR

object ResultFormatFactory:
  export org.openjdk.jmh.results.format.ResultFormatFactory.getInstance

object OutputFormatFactory:
  export org.openjdk.jmh.runner.format.OutputFormatFactory.createFormatInstance
