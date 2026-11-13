package scala.scalanative.jmh

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

trait MeasurementMath {
  private[jmh] def workerParams(index: Int, count: Int): ThreadParams =
    new ThreadParams(index, count, index, count, 0, 1, 0, 1, 0, 1)

  private[jmh] def operations(
      mode: Mode,
      configuration: Configuration,
      workers: Vector[RawResult],
      batchSize: Int,
      all: Boolean
  ): Long = workers.map { worker =>
    if (mode == Mode.SingleShotTime) configuration.operationsPerInvocation.toLong
    else {
      val count = if (all && worker.allInvocations >= 0) worker.allInvocations else worker.invocations
      if (mode == Mode.SampleTime) {
        val batches = worker.invocations / batchSize
        val operations = if (all) {
          val extra = count - worker.invocations
          (extra.toDouble + batches.toDouble * batchSize) * configuration.operationsPerInvocation / batchSize
        } else batches.toDouble * configuration.operationsPerInvocation
        operations.toLong
      } else (count.toDouble * configuration.operationsPerInvocation / batchSize).toLong
    }
  }.sum

  def unitNanos(unit: String): Double = TimeUnit.valueOf(unit).toNanos(1).toDouble

  def aggregate(
      mode: Mode,
      configuration: Configuration,
      workers: Vector[RawResult]
  ): Double = {
    val scale = unitNanos(configuration.unit)
    val scores = workers.map { result =>
      val operations =
        if (mode == Mode.SingleShotTime)
          configuration.operationsPerInvocation.toDouble
        else
          result.invocations.toDouble * configuration.operationsPerInvocation / configuration.measurementBatchSize
      if (mode == Mode.Throughput) operations * scale / result.elapsedNanos
      else result.elapsedNanos / operations / scale
    }
    if (mode == Mode.Throughput) scores.sum else scores.sum / scores.size
  }

}
