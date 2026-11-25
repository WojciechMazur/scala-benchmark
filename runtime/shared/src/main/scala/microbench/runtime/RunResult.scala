/*
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
package microbench.runtime

import scala.jdk.CollectionConverters.*

import org.openjdk.jmh.annotations.Mode

/** Measurements of one benchmark in one mode, with the statistics JMH reports for them. */
final case class RunResult(
    descriptor: Descriptor,
    mode: Mode,
    configuration: Configuration,
    parameters: Map[String, String],
    forks: Vector[Vector[IterationResult]],
    clock: ClockCalibration,
    metricKind: String = "",
    statisticsOverride: Option[Statistics] = None,
    publicMetric: Option[org.openjdk.jmh.results.Result[?]] = None,
    secondaryOverride: Option[Vector[(String, RunResult)]] = None,
    histogramOverride: Option[Vector[Vector[Vector[(Double, Long)]]]] = None,
    reportParams: Option[org.openjdk.jmh.infra.BenchmarkParams] = None
):

  lazy val statistics: Statistics = statisticsOverride.getOrElse(if publicMetric.nonEmpty then
    Statistics(
      publicMetric.get
        .getStatistics()
        .getRawData()
        .asScala
        .map(e => e.getKey.doubleValue() -> e.getValue.longValue())
        .toVector,
      sampled = mode == Mode.SampleTime && metricKind.isEmpty
    )
  else if mode == Mode.SampleTime && metricKind.isEmpty then
    val histogram = new SampleBuffer
    forks.flatten.flatMap(_.workers).foreach(r => histogram.merge(r.samples))
    Statistics(
      histogram.entries.map { case (v, n) =>
        v / Execution.unitNanos(configuration.unit) -> n
      },
      sampled = true
    )
  else Statistics(forks.flatten.map(r => r.score -> 1L)))

  /** Member observations retain their original worker/fork/iteration boundaries. */
  lazy val secondaryResults: Vector[(String, RunResult)] = secondaryOverride.getOrElse {
    val base = groupResults ++ auxiliaryResults
    base ++ percentileResults("") ++ base.flatMap { case (name, value) => value.percentileResults(name + ":") }
  }

  private def percentileResults(prefix: String): Vector[(String, RunResult)] =
    if mode != Mode.SampleTime || metricKind.nonEmpty then Vector.empty
    else
      Vector(
        "p0.00" -> 0.0,
        "p0.50" -> 50.0,
        "p0.90" -> 90.0,
        "p0.95" -> 95.0,
        "p0.99" -> 99.0,
        "p0.999" -> 99.9,
        "p0.9999" -> 99.99,
        "p1.00" -> 100.0
      ).map { case (name, point) =>
        val observations = forks.map(_.map { iteration =>
          val buffer = new SampleBuffer
          iteration.workers.foreach(worker => buffer.merge(worker.samples))
          val stats = Statistics(
            buffer.entries.map { case (v, n) => v / Execution.unitNanos(configuration.unit) -> n },
            sampled = true
          )
          iteration.copy(score = stats.percentile(point))
        })
        (prefix + name) -> copy(
          descriptor =
            descriptor.copy(name = descriptor.name + ":" + name, members = Vector.empty, counters = Vector.empty),
          forks = observations,
          metricKind = "DERIVATIVE",
          statisticsOverride = Some(Statistics(Vector(statistics.percentile(point) -> 1L)))
        )
      }

  private lazy val groupResults: Vector[(String, RunResult)] =
    if descriptor.members.size <= 1 then Vector.empty
    else
      descriptor.members.zipWithIndex.filter(_._1.threads > 0).map { case (member, subgroup) =>
        val observations = forks.map(_.map { iteration =>
          val workers = iteration.workers.zipWithIndex.collect {
            case (worker, index) if descriptor.worker(index, configuration.threads).getSubgroupIndex() == subgroup =>
              worker
          }
          IterationResult(Execution.aggregate(mode, configuration, workers), workers)
        })
        val threads = configuration.threads / descriptor.members.map(_.threads).sum * member.threads
        member.name -> copy(
          descriptor = descriptor
            .copy(name = descriptor.name + ":" + member.name, members = Vector.empty, counters = Vector.empty),
          configuration = configuration.copy(threads = threads),
          forks = observations
        )
      }

  private lazy val auxiliaryResults: Vector[(String, RunResult)] =
    if descriptor.counters.isEmpty then Vector.empty
    else
      val observed = forks.flatten.flatMap(_.workers.flatMap(_.auxiliary.map(c => c.name -> c.kind))).toSet
      descriptor.counters.map(c => c.name -> c.kind).distinct.filter(observed).collect {
        case (name, kind) if kind == "EVENTS" || mode == Mode.Throughput || mode == Mode.AverageTime =>
          val observations = forks.map(_.flatMap { iteration =>
            val counters = iteration.workers.flatMap { worker =>
              worker.auxiliary.filter(c => c.name == name && c.kind == kind).map(c => worker -> c.value)
            }
            val score =
              if kind == "EVENTS" then counters.map(_._2).sum
              else
                val values = counters.map { case (worker, value) =>
                  if mode == Mode.Throughput then value * Execution.unitNanos(configuration.unit) / worker.elapsedNanos
                  else worker.elapsedNanos / value / Execution.unitNanos(configuration.unit)
                }
                if mode == Mode.Throughput then values.sum else values.sum / values.size
            // Scalar EVENTS provide a zero result; rate/time OPERATIONS do not.
            // JMH omits missing operation observations from iteration aggregation.
            Option.when(counters.nonEmpty)(IterationResult(score, counters.map(_._1)))
          })
          name -> copy(
            descriptor =
              descriptor.copy(name = descriptor.name + ":" + name, members = Vector.empty, counters = Vector.empty),
            forks = observations,
            metricKind = kind,
            statisticsOverride = if kind == "EVENTS" then
              val scores = observations.flatten.map(_.score -> 1L)
              Some(Statistics(scores ++ Vector.fill(forks.flatten.size - scores.size)(0.0 -> 1L)))
            else None
          )
      }

  def sampleCount: Long = publicMetric.fold(statistics.count)(_.getSampleCount())

  def percentile(rank: Double): Double =
    publicMetric.fold(statistics.percentile(rank))(_.getStatistics().getPercentile(rank))

  def score: Double = publicMetric.fold(
    if metricKind == "EVENTS" then statistics.values.map { case (v, n) => v * n }.sum else statistics.mean
  )(_.getScore())

  def scoreError: Double =
    publicMetric.fold(if metricKind == "EVENTS" then Double.NaN else statistics.error)(_.getScoreError())

  def scoreConfidence: Vector[Double] = publicMetric.fold(
    if metricKind == "EVENTS" then Vector(score, score) else Vector(score - scoreError, score + scoreError)
  )(_.getScoreConfidence().toVector)

  def unit: String =
    if publicMetric.nonEmpty then return publicMetric.get.getScoreUnit()
    if metricKind == "EVENTS" then return "#"
    val suffix = configuration.unit match
      case "NANOSECONDS"  => "ns"
      case "MICROSECONDS" => "us"
      case "MILLISECONDS" => "ms"
      case "SECONDS"      => "s"
      case "MINUTES"      => "min"
      case "HOURS"        => "hr"
      case "DAYS"         => "day"
    if mode == Mode.Throughput then "ops/" + suffix else suffix + "/op"

end RunResult
