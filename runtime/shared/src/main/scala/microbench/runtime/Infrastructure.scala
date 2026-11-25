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

import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.infra.{BenchmarkParams, IterationParams}
import org.openjdk.jmh.runner.{IterationType, WorkloadParams}
import org.openjdk.jmh.runner.options.TimeValue

private[runtime] object Infrastructure:

  def benchmark(
      descriptor: Descriptor,
      mode: Mode,
      c: Configuration,
      parameters: Map[String, String]
  ): BenchmarkParams =
    val workload = new WorkloadParams
    parameters.toVector.sorted.foreach { case (key, value) =>
      val choices = descriptor.states.flatMap(_.parameters).find(_.name == key).map(_.values).getOrElse(Vector.empty)
      workload.put(key, value, c.parameterOrder.getOrElse(key, math.max(0, choices.indexOf(value))))
    }
    new BenchmarkParams(
      descriptor.name,
      descriptor.generatedTarget,
      c.synchIterations,
      c.threads,
      if descriptor.members.isEmpty then Array(1) else descriptor.members.map(_.threads).toArray,
      (if descriptor.members.size > 1 then descriptor.members.map(_.name) else Vector.empty[String]).asJava,
      c.forks,
      c.warmupForks,
      iteration(c, true),
      iteration(c, false),
      mode,
      workload,
      TimeUnit.valueOf(c.unit),
      c.operationsPerInvocation,
      "N/A",
      Vector.empty[String].asJava,
      "N/A",
      Platform.description,
      Platform.runtimeVersion,
      "1.37",
      Configuration.timeout(c)
    )

  end benchmark

  def iteration(c: Configuration, warmup: Boolean): IterationParams =
    new IterationParams(
      if warmup then IterationType.WARMUP else IterationType.MEASUREMENT,
      if warmup then c.warmupIterations else c.measurementIterations,
      Configuration.time(c, warmup),
      if warmup then c.warmupBatchSize else c.measurementBatchSize
    )

end Infrastructure
