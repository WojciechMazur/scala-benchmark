/*
 * Copyright (c) 2026, Wojciech Mazur.
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

import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.infra.ThreadParams

final case class TrialMetadata(
    startMillis: Long,
    measurementStartMillis: Long,
    stopMillis: Long,
    warmupOperations: Long,
    measurementOperations: Long
)

final case class IterationResult(score: Double, workers: Vector[RawResult], trial: Option[TrialMetadata] = None)

trait MeasurementMath:

  private[runtime] def workerParams(index: Int, count: Int): ThreadParams =
    new ThreadParams(index, count, index, count, 0, 1, 0, 1, 0, 1)

  private[runtime] def operations(
      mode: Mode,
      configuration: Configuration,
      workers: Vector[RawResult],
      batchSize: Int,
      all: Boolean
  ): Long = workers.map { worker =>
    if mode == Mode.SingleShotTime then configuration.operationsPerInvocation.toLong
    else
      val count = if all && worker.allInvocations >= 0 then worker.allInvocations else worker.invocations
      if mode == Mode.SampleTime then
        val batches = worker.invocations / batchSize
        val operations = if all then
          val extra = count - worker.invocations
          (extra.toDouble + batches.toDouble * batchSize) * configuration.operationsPerInvocation / batchSize
        else batches.toDouble * configuration.operationsPerInvocation
        operations.toLong
      else (count.toDouble * configuration.operationsPerInvocation / batchSize).toLong
  }.sum

  def unitNanos(unit: String): Double = TimeUnit.valueOf(unit).toNanos(1).toDouble

  def aggregate(
      mode: Mode,
      configuration: Configuration,
      workers: Vector[RawResult]
  ): Double =
    val scale = unitNanos(configuration.unit)
    val scores = workers.map { result =>
      val operations =
        if mode == Mode.SingleShotTime then
          configuration.operationsPerInvocation.toDouble
        else
          result.invocations.toDouble * configuration.operationsPerInvocation / configuration.measurementBatchSize
      if mode == Mode.Throughput then operations * scale / result.elapsedNanos
      else result.elapsedNanos / operations / scale
    }
    if mode == Mode.Throughput then scores.sum else scores.sum / scores.size

end MeasurementMath
