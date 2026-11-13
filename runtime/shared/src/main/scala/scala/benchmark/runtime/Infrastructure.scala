package scala.benchmark.runtime

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.infra.{BenchmarkParams, IterationParams}
import org.openjdk.jmh.runner.{IterationType, WorkloadParams}
import org.openjdk.jmh.runner.options.TimeValue
import scala.jdk.CollectionConverters.*

private[runtime] object Infrastructure {
  def benchmark(
      descriptor: Descriptor,
      mode: Mode,
      c: Configuration,
      parameters: Map[String, String]
  ): BenchmarkParams = {
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
      (if (descriptor.members.isEmpty) Array(1) else descriptor.members.map(_.threads).toArray),
      (if (descriptor.members.size > 1) descriptor.members.map(_.name) else Vector.empty[String]).asJava,
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
  }
  def iteration(c: Configuration, warmup: Boolean): IterationParams =
    new IterationParams(
      if (warmup) IterationType.WARMUP else IterationType.MEASUREMENT,
      if (warmup) c.warmupIterations else c.measurementIterations,
      Configuration.time(c, warmup),
      if (warmup) c.warmupBatchSize else c.measurementBatchSize
    )
}
