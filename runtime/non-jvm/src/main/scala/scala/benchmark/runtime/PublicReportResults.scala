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

import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.results.RunResult as PublicRunResult

/** Public report views preserve observations and metadata without manufacturing worker measurements. */
private[runtime] object PublicReportResults:

  def apply(result: PublicRunResult): RunResult =
    val params = result.getParams()
    val warmup = params.getWarmup()
    val measurement = params.getMeasurement()
    val c = Configuration(
      warmupIterations = warmup.getCount(),
      measurementIterations = measurement.getCount(),
      warmupNanos = warmup.getTime().convertTo(TimeUnit.NANOSECONDS),
      measurementNanos = measurement.getTime().convertTo(TimeUnit.NANOSECONDS),
      warmupBatchSize = warmup.getBatchSize(),
      measurementBatchSize = measurement.getBatchSize(),
      forks = params.getForks(),
      warmupForks = params.getWarmupForks(),
      threads = params.getThreads(),
      operationsPerInvocation = params.getOpsPerInvocation(),
      timeoutNanos = params.getTimeout().convertTo(TimeUnit.NANOSECONDS),
      unit = params.getTimeUnit().name(),
      synchIterations = params.shouldSynchIterations(),
      threadGroups = params.getThreadGroups().toVector,
      warmupTime = warmup.getTime(),
      measurementTime = measurement.getTime(),
      timeoutTime = params.getTimeout()
    )
    val descriptor = Descriptor(
      0,
      params.getBenchmark(),
      Vector(params.getMode()),
      c,
      Vector.empty,
      Vector.empty,
      generatedTarget = params.generatedBenchmark()
    )
    val context = RunResult(
      descriptor,
      params.getMode(),
      c,
      params.getParamsKeys().asScala.map(key => key -> params.getParam(key)).toMap,
      Vector.empty,
      ClockCalibration.Unavailable
    )
    apply(result, context)

  end apply

  def apply(result: PublicRunResult, context: RunResult): RunResult =
    def metric(label: Option[String], value: org.openjdk.jmh.results.Result[?]): RunResult =
      val observations = result.getBenchmarkResults().asScala.toVector.map { benchmark =>
        benchmark.getIterationResults().asScala.toVector.flatMap { iteration =>
          val observed = label
            .fold(Option(iteration.getPrimaryResult()))(name => Option(iteration.getSecondaryResults().get(name)))
          observed.map(v => IterationResult(v.getScore(), Vector.empty))
        }
      }
      val histogram =
        if context.mode == Mode.SampleTime && label.isEmpty then
          Some(
            result
              .getBenchmarkResults()
              .asScala
              .toVector
              .map(
                _.getIterationResults().asScala.toVector.map(
                  _.getPrimaryResult()
                    .getStatistics()
                    .getRawData()
                    .asScala
                    .map(e => e.getKey.doubleValue() -> e.getValue.longValue())
                    .toVector
                )
              )
          )
        else None
      context.copy(
        descriptor = context.descriptor.copy(name = context.descriptor.name + label.fold("")(name => ":" + name)),
        forks = observations,
        metricKind = if label.isEmpty then "" else "PUBLIC",
        statisticsOverride = None,
        publicMetric = Some(value),
        reportParams = Some(result.getParams()),
        secondaryOverride = Some(Vector.empty),
        histogramOverride = histogram
      )
    end metric
    val primary = metric(None, result.getPrimaryResult())
    primary.copy(secondaryOverride = Some(result.getSecondaryResults().asScala.toVector.map { case (name, value) =>
      name -> metric(Some(name), value)
    }))

  end apply

end PublicReportResults
