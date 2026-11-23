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

import microbench.internal.reporting.JsonString
import org.openjdk.jmh.annotations.Mode

/** JMH-compatible JSON results. */
private[runtime] object JsonReport:

  def quote(text: String): String = JsonString.quote(text)

  private def number(value: Double): String =
    if value.isFinite then value.toString
    else if value == Double.PositiveInfinity then quote("+INF")
    else if value == Double.NegativeInfinity then quote("-INF")
    else quote("NaN")

  private def array(items: Iterable[String]): String =
    items.mkString("[", ",", "]")

  private def obj(items: Iterable[(String, String)]): String = items
    .map { case (key, value) => quote(key) + ":" + value }
    .mkString("{", ",", "}")

  private def metricJson(run: RunResult): String =
    val s = run.statistics
    val c = run.configuration
    val raw =
      if run.metricKind.isEmpty && !microbench.internal.JsonReportOptions.rawData then
        (if run.mode == Mode.SampleTime then "rawDataHistogram" else "rawData") -> "[]"
      else if run.histogramOverride.nonEmpty then
        "rawDataHistogram" -> array(
          run.histogramOverride.get.map(fork =>
            array(
              fork.map(iteration =>
                array(iteration.map { case (value, count) => array(Vector(number(value), count.toString)) })
              )
            )
          )
        )
      else if run.mode == Mode.SampleTime && run.metricKind.isEmpty then
        "rawDataHistogram" -> array(
          run.forks.map(fork =>
            array(fork.map { iteration =>
              val merged = new SampleBuffer
              iteration.workers.foreach(r => merged.merge(r.samples))
              array(merged.entries.map { case (v, n) =>
                array(
                  Vector(number(v / Execution.unitNanos(c.unit)), n.toString)
                )
              })
            })
          )
        )
      else
        "rawData" -> array(
          run.forks.map(f => array(f.map(r => number(r.score))))
        )
    obj(
      Vector(
        "score" -> number(run.score),
        "scoreError" -> number(run.scoreError),
        "scoreConfidence" -> array(
          run.scoreConfidence.map(number)
        ),
        "scoreUnit" -> quote(run.unit),
        "scorePercentiles" -> obj(
          Vector("0.0", "50.0", "90.0", "95.0", "99.0", "99.9", "99.99", "99.999", "99.9999", "100.0")
            .map(p => p -> number(run.percentile(p.toDouble)))
        ),
        raw
      )
    )

  end metricJson

  def render(results: Vector[RunResult]): String =
    microbench.internal.JsonReportOptions.initialize()
    array(results.map { run =>
      val c = run.configuration
      val nativeMetadata = obj(
        (Vector(
          "os" -> quote(System.getProperty("os.name", "unknown")),
          "arch" -> quote(System.getProperty("os.arch", "unknown")),
          "ignoredSettings" -> array(run.descriptor.warnings.map(quote))
        ) ++
          Option
            .when(run.clock.available)(
              Vector(
                "clockMinimumDeltaNanos" -> run.clock.minimumDeltaNanos.toString,
                "clockAverageCallNanos" -> number(run.clock.averageCallNanos)
              )
            )
            .toVector
            .flatten ++ Platform.buildMetadata.map { case (k, v) => k -> quote(v) }).toMap.toVector.sortBy(_._1)
      )
      val supplied = run.reportParams
      val jvm = Platform.jvmInfo
      obj(
        Vector(
          "jmhVersion" -> quote(supplied.fold(Platform.description + " (JMH API 1.37)")(_.getJmhVersion())),
          "benchmark" -> quote(run.descriptor.name),
          "mode" -> quote(run.mode.shortLabel()),
          "threads" -> c.threads.toString,
          "forks" -> c.forks.toString,
          "jvm" -> quote(supplied.fold(jvm.fold("N/A")(_.jvm))(_.getJvm())),
          "jvmArgs" -> array(
            supplied.fold(jvm.toVector.flatMap(_.jvmArgs))(_.getJvmArgs().asScala.toVector).map(quote)
          ),
          "jdkVersion" -> quote(supplied.fold(jvm.fold("N/A")(_.jdkVersion))(_.getJdkVersion())),
          "vmName" -> quote(supplied.fold(jvm.fold(Platform.description)(_.vmName))(_.getVmName())),
          "vmVersion" -> quote(supplied.fold(jvm.fold(Platform.runtimeVersion)(_.vmVersion))(_.getVmVersion())),
          "warmupIterations" -> c.warmupIterations.toString,
          "warmupTime" -> quote(Configuration.time(c, true).toString),
          "warmupBatchSize" -> c.warmupBatchSize.toString,
          "measurementIterations" -> c.measurementIterations.toString,
          "measurementTime" -> quote(Configuration.time(c, false).toString),
          "measurementBatchSize" -> c.measurementBatchSize.toString,
          "params" -> obj(run.parameters.toVector.sortBy(_._1).map { case (k, v) =>
            k -> quote(v)
          }),
          "primaryMetric" -> metricJson(run),
          "secondaryMetrics" -> obj(run.secondaryResults.map { case (name, member) => name -> metricJson(member) })
        ) ++ Option.when(run.clock.available)(Platform.metadataSection -> nativeMetadata)
      )
    })

  end render

end JsonReport
