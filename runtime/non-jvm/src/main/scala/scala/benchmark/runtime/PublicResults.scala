package scala.benchmark.runtime

import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.results.{
  AggregationPolicy,
  IterationResultMetaData,
  MetricResult,
  Result,
  ResultRole,
  ResultSnapshots
}
import org.openjdk.jmh.util.MultisetStatistics
import scala.jdk.CollectionConverters.*

private[runtime] object PublicResults {
  private def metric(run: RunResult, role: ResultRole, label: String, worker: Boolean = false): Result[?] = {
    val statistics: org.openjdk.jmh.util.Statistics =
      if (run.mode == Mode.SampleTime && run.metricKind.isEmpty) {
        val values = new MultisetStatistics
        run.statistics.values.foreach { case (value, count) => values.addValue(value, count) }
        values
      } else if (worker && run.statistics.values.size == 1 && run.statistics.values.head._2 == 1L)
        new org.openjdk.jmh.util.SingletonStatistics(run.statistics.values.head._1)
      else {
        val values = new org.openjdk.jmh.util.ListStatistics
        run.statistics.values.foreach { case (value, count) =>
          var index = 0L
          while (index < count) { values.addValue(value); index += 1 }
        }
        values
      }
    org.openjdk.jmh.results.StandardResults.metric(
      run.mode,
      role,
      label,
      statistics,
      run.unit,
      if (run.metricKind == "EVENTS" || (worker && run.mode == Mode.Throughput)) AggregationPolicy.SUM
      else AggregationPolicy.AVG,
      scalar = run.metricKind == "EVENTS",
      derivative = run.metricKind == "DERIVATIVE"
    )
  }
  private def secondary(run: RunResult): java.util.Map[String, Result[?]] = {
    val result = new java.util.TreeMap[String, Result[?]]
    run.secondaryResults.foreach { case (label, observation) =>
      result.put(label, metric(observation, ResultRole.SECONDARY, label))
    }
    java.util.Collections.unmodifiableMap(result)
  }
  def iteration(
      d: Descriptor,
      mode: Mode,
      c: Configuration,
      parameters: Map[String, String],
      warmup: Boolean,
      result: IterationResult
  ): org.openjdk.jmh.results.IterationResult = {
    val params = Infrastructure.benchmark(d, mode, c, parameters)
    val scoring =
      if (warmup)
        c.copy(
          measurementBatchSize = c.warmupBatchSize,
          measurementNanos = c.warmupNanos,
          measurementTime = c.warmupTime
        )
      else c
    val converted = apply(RunResult(d, mode, scoring, parameters, Vector(Vector(result)), ClockCalibration(1, 1)))
      .getBenchmarkResults()
      .iterator()
      .next()
      .getIterationResults()
      .iterator()
      .next()
    val value = new org.openjdk.jmh.results.IterationResult(
      params,
      if (warmup) params.getWarmup() else params.getMeasurement(),
      converted.getMetadata()
    )
    value.addResults(converted.getRawPrimaryResults())
    value.addResults(converted.getRawSecondaryResults().values())
    value
  }
  def apply(run: RunResult): org.openjdk.jmh.results.RunResult = {
    val params = Infrastructure.benchmark(run.descriptor, run.mode, run.configuration, run.parameters)
    val forks = run.forks.map { fork =>
      val iterations = fork.map { iteration =>
        val view = run.copy(forks = Vector(Vector(iteration)))
        val raw = iteration.workers.map { worker =>
          val observation = run.copy(
            descriptor = run.descriptor.copy(members = Vector.empty, counters = Vector.empty),
            forks = Vector(
              Vector(IterationResult(Execution.aggregate(run.mode, run.configuration, Vector(worker)), Vector(worker)))
            )
          )
          metric(observation, ResultRole.PRIMARY, run.descriptor.name.split('.').last, worker = true)
        }
        val rawSecondary = iteration.workers.zipWithIndex.flatMap { case (worker, index) =>
          val perWorker = run.copy(forks =
            Vector(
              Vector(IterationResult(Execution.aggregate(run.mode, run.configuration, Vector(worker)), Vector(worker)))
            )
          )
          val member = if (run.descriptor.members.size > 1) {
            val label =
              run.descriptor.members(run.descriptor.worker(index, run.configuration.threads).getSubgroupIndex()).name
            Vector(
              metric(
                perWorker.copy(descriptor = perWorker.descriptor.copy(members = Vector.empty, counters = Vector.empty)),
                ResultRole.SECONDARY,
                label,
                worker = true
              )
            )
          } else Vector.empty
          // Counter observations are worker-local; group/member labels are retained separately.
          val counters = perWorker
            .copy(descriptor =
              perWorker.descriptor.copy(
                members = Vector.empty,
                counters = perWorker.descriptor.counters.filter(c =>
                  worker.auxiliary.exists(v => v.name == c.name && v.kind == c.kind)
                )
              )
            )
            .secondaryResults
            .filterNot(_._2.metricKind == "DERIVATIVE")
            .map { case (label, value) =>
              metric(value, ResultRole.SECONDARY, label, worker = true)
            }
          member ++ counters
        }
        def operations(all: Boolean): Long = Execution.operations(
          run.mode,
          run.configuration,
          iteration.workers,
          run.configuration.measurementBatchSize,
          all
        )
        ResultSnapshots.iteration(
          params,
          params.getMeasurement(),
          new IterationResultMetaData(operations(true), operations(false)),
          raw.asJava,
          rawSecondary.asJava,
          metric(view, ResultRole.PRIMARY, run.descriptor.name.split('.').last),
          secondary(view)
        )
      }
      val view = run.copy(forks = Vector(fork))
      val metadata = fork.headOption
        .flatMap(_.trial)
        .map { value =>
          new org.openjdk.jmh.results.BenchmarkResultMetaData(
            value.startMillis,
            value.measurementStartMillis,
            value.stopMillis,
            value.warmupOperations,
            value.measurementOperations
          )
        }
        .orNull
      ResultSnapshots.benchmark(
        params,
        new java.util.ArrayList(iterations.asJava),
        metric(view, ResultRole.PRIMARY, run.descriptor.name.split('.').last),
        secondary(view),
        metadata
      )
    }
    ResultSnapshots.run(
      params,
      new java.util.ArrayList(forks.asJava),
      metric(run, ResultRole.PRIMARY, run.descriptor.name.split('.').last),
      secondary(run)
    )
  }
}
