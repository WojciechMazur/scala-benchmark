package scala.benchmark.runtime

import org.openjdk.jmh.annotations.Mode

/** Validate transported observations before a child can contribute a successful result. */
private[runtime] object ForkResults:

  def verify(
      mode: Mode,
      configuration: Configuration,
      results: Vector[IterationResult],
      requireTrialMetadata: Boolean = true
  ): Vector[IterationResult] =
    require(results.size == configuration.measurementIterations, "Incomplete fork measurement iterations")
    require(
      (!requireTrialMetadata && results.forall(_.trial.isEmpty)) ||
        (results.forall(_.trial.nonEmpty) && results.flatMap(_.trial).distinct.size == 1),
      "Inconsistent fork trial metadata"
    )
    results.foreach { iteration =>
      require(iteration.workers.size == configuration.threads, "Incomplete fork worker observations")
      iteration.workers.foreach { worker =>
        require(
          worker.invocations > 0 && worker.elapsedNanos >= 0 &&
            (mode == Mode.SingleShotTime || worker.elapsedNanos > 0),
          "Invalid fork work or clock interval"
        )
        require(
          worker.allInvocations == -1 || worker.allInvocations >= worker.invocations,
          "Invalid fork total invocation count"
        )
        if mode == Mode.SingleShotTime then
          require(worker.invocations == configuration.measurementBatchSize, "Incomplete single-shot batch")
        if mode == Mode.SampleTime then
          require(worker.invocations % configuration.measurementBatchSize == 0, "Incomplete sample batch")
          val population = worker.samples.entries.map(_._2).sum
          require(
            population > 0 && population <= worker.invocations / configuration.measurementBatchSize,
            "Invalid fork sample population"
          )
      }
      val computed = Execution.aggregate(mode, configuration, iteration.workers)
      val agrees = iteration.score == computed ||
        (computed != 0 && math.abs((iteration.score - computed) / computed) <= 1e-12)
      require(
        iteration.score.isFinite && computed.isFinite && agrees,
        "Fork score does not match its raw observations"
      )
    }
    results

  end verify

end ForkResults
