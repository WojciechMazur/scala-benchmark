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
package scala.benchmark.runtime

import org.openjdk.jmh.annotations.Mode

object BenchmarkRunner:

  def run(options: Options, observer: RunObserver = RunObserver.Disabled): Vector[RunResult] =
    Platform.outputContext(options)(BenchmarkOutput.capture(observer.output(options)(execute(options, observer))))

  private def execute(options: Options, observer: RunObserver): Vector[RunResult] =
    if options.help then
      println(Options.help); return Vector.empty
    if options.listFormats then
      println("Available formats: " + Reports.formats.mkString(", ")); return Vector.empty
    val suites = (Platform.inventory ++ options.suites).distinct.sorted
    require(
      suites.nonEmpty,
      "No benchmarks found. Enable JmhPlugin on a project that uses sbt-scala-benchmark."
    )
    val inventory = suites
      .flatMap { name =>
        val suite = Platform.loadSuite(name)
        suite.metadata().toVector.zipWithIndex.map { case (metadata, index) =>
          (name, suite, metadata, Descriptor.of(index, metadata))
        }
      }
    val discovered = inventory.groupBy(_._4.name).toVector.sortBy(_._1).map { case (_, members) =>
      val (suiteName, suite, text, first) = members.head
      val eligibility = scala.benchmark.internal.Eligibility(
        members.foldLeft(scala.benchmark.Platform.values.toVector)((allowed, m) =>
          allowed.filter(m._4.eligibility.allowed.contains)
        ),
        members.flatMap(_._4.eligibility.reasons).distinct
      )
      (suiteName, suite, text, first.copy(eligibility = eligibility))
    }
    val selection = scala.benchmark.internal.Selection(
      discovered,
      options.includes,
      options.excludes,
      Platform.identity,
      options.list
    )(_._4.name, _._4.eligibility)
    val selected = selection.selected
    val warmupSelection =
      if options.list || options.warmupIncludes.isEmpty then None
      else
        Some(
          scala.benchmark.internal.Selection(discovered, options.warmupIncludes, options.excludes, Platform.identity)(
            _._4.name,
            _._4.eligibility
          )
        )
    val extraWarmups = warmupSelection.toVector.flatMap(_.selected)
    val warmupSkips = warmupSelection.toVector.flatMap(_.skipped)
    (selection.skipped ++ warmupSkips).distinct.foreach { case (_, _, _, descriptor) =>
      println("# SKIPPED: " + descriptor.eligibility.diagnostic(descriptor.name, Platform.identity))
    }
    if !options.list && selected.isEmpty then return Vector.empty
    if options.list then
      selected.foreach { case (_, _, _, descriptor) =>
        println(s"${descriptor.name} [${descriptor.eligibility.description}]")
        if options.listParameters then
          descriptor.states
            .flatMap(_.parameters)
            .distinct
            .foreach(p => println(s"  ${p.name}: ${options.parameters.getOrElse(p.name, p.values).mkString(", ")}"))
      }
      return Vector.empty
    Platform.ensureMeasurementHealthy()
    ForkOutputFailure.check()
    options.output.foreach(Platform.validateOutput)
    def expandedModes(modes: Vector[Mode]): Vector[Mode] =
      if modes.contains(Mode.All) then Vector(Mode.Throughput, Mode.AverageTime, Mode.SampleTime, Mode.SingleShotTime)
      else modes.distinct
    val bulkCandidates = (extraWarmups ++ (if options.warmupMode != "INDI" then selected else Vector.empty)).distinct
    val warmups = bulkCandidates.flatMap { case (suiteName, _, text, descriptor) =>
      expandedModes(if options.modes.nonEmpty then options.modes else descriptor.modes).flatMap { mode =>
        val parsed = Descriptor.of(descriptor.index, text, Some(mode))
        val overrides = options.configuration(parsed.configuration)
        val actual = parsed.configured(overrides)
        val config = actual.resolve(overrides)
        Platform.validate(config)
        Descriptor
          .indexedCombinations(actual, options.parameters)
          .map { case (p, orders) =>
            WarmupRequest(suiteName, descriptor.index, mode, config.copy(parameterOrder = orders), p)
          }
      }
    }
    observer.startRun()
    var embeddedWarmed = false
    val clock = ClockCalibration.measure()
    println(
      s"# Scala Benchmark, JMH API 1.37; ${Platform.description}; clock delta >= ${clock.minimumDeltaNanos} ns, average call ${clock.averageCallNanos} ns"
    )
    Platform.buildWarnings.foreach(w => Console.err.println("# WARNING: " + w))
    var completed = Vector.empty[RunResult]
    def save(): Unit = options.output.foreach { output =>
      Platform.write(output, Reports.render(observer.reportResults(completed), options.format))
    }
    var primary: Throwable = null
    try
      selected.foreach { case (suiteName, suite, metadata, descriptor) =>
        expandedModes(if options.modes.nonEmpty then options.modes else descriptor.modes).foreach { mode =>
          val parsed = Descriptor.of(descriptor.index, metadata, Some(mode))
          val overrides = options.configuration(parsed.configuration)
          val actual = parsed.configured(overrides)
          val resolved = actual.resolve(overrides)
          val actionConfig =
            if options.warmupMode == "BULK" then
              resolved.copy(
                warmupIterations = 0,
                warmupNanos = 0,
                warmupBatchSize = 1,
                warmupTime = org.openjdk.jmh.runner.options.TimeValue.NONE
              )
            else resolved
          val baseConfig =
            if mode == Mode.SingleShotTime then actionConfig.copy(synchIterations = false) else actionConfig
          Platform.validate(baseConfig)
          if options.verbosity == "EXTRA" then println(s"# Configuration ${actual.name}: $baseConfig")
          actual.warnings.foreach(w => Console.err.println("# WARNING: " + w))
          Descriptor.indexedCombinations(actual, options.parameters).foreach { case (parameters, orders) =>
            val config = baseConfig.copy(parameterOrder = orders)
            try
              val parameterText = parameters.toVector.sorted.map { case (key, value) => s"$key=$value" }.mkString(", ")
              val detail = if parameterText.isEmpty then "" else s"; $parameterText"
              println(s"# Running ${actual.name} [${mode.shortLabel()}$detail]")
              val result = if config.forks == 0 then
                Console.err.println(
                  "# WARNING: forks=0 runs without process isolation"
                )
                if !embeddedWarmed then
                  WarmupRequest.execute(warmups, failOnError = options.failOnError, observer = observer);
                  embeddedWarmed = true
                observer.startBenchmark(actual, mode, config, parameters)
                Vector(
                  Execution.trial(
                    suite,
                    actual,
                    mode,
                    config,
                    parameters,
                    retainCompletedOnFailure = true,
                    observer = observer.trial(actual, mode, config, parameters)
                  )
                )
              else
                observer.startBenchmark(actual, mode, config, parameters)
                Vector
                  .tabulate(config.warmupForks + config.forks) { fork =>
                    val result = try
                      Some(
                        Platform
                          .forked(suiteName, actual, mode, config, parameters, warmups, options.failOnError, observer)
                      )
                    catch
                      case failure: BenchmarkForkFailure
                          if !options.failOnError && !ForkOutputFailure.failed && !Platform.isCallerInterrupted =>
                        Console.err.println(
                          s"# FAILED fork ${fork + 1} of ${config.warmupForks + config.forks} for ${actual.name}: ${failure.getMessage}"
                        )
                        if failure.completed.nonEmpty then Some(failure.completed) else None
                    if fork < config.warmupForks then None else result
                  }
                  .flatten
              if result.isEmpty then
                if config.forks > 0 then observer.endBenchmark(None, forked = true)
                throw new BenchmarkForkFailure("Benchmark failure", "No measurement forks completed", Vector.empty)
              if
                config.forks > 0 &&
                (result.size != config.forks || result.exists(
                  _.size != config.measurementIterations
                ))
              then
                Console.err.println(
                  s"# INCOMPLETE ${actual.name}: retained ${result.size} of ${config.forks} measurement forks, iteration counts ${result.map(_.size).mkString(",")}; failed observations excluded"
                )
              val run =
                RunResult(actual, mode, config, parameters, result, clock)
              observer.endBenchmark(Some(run), forked = config.forks > 0)
              completed :+= run
              save()
            catch
              case t: Throwable =>
                if !ForkOutputFailure.failed && !Platform.isCallerInterrupted then
                  Console.err.println(
                    s"# FAILED ${actual.name}: ${t.getMessage}"
                  )
                if mustPropagate(t, options.failOnError) then throw t
                if config.forks == 0 && BenchmarkForkFailure.completed(t).nonEmpty then
                  throw new IncompleteEmbeddedTrialException(t)
            end try

          }
        }
      }
    catch
      case error: Throwable =>
        primary = error
        throw error
    finally
      if !Platform.isCallerInterrupted then
        try save()
        catch case error: Throwable => if primary != null then primary.addSuppressed(error) else throw error
        if !ForkOutputFailure.failed && !observer.displaysResults then
          try Reports.console(completed)
          catch case error: Throwable => if primary != null then primary.addSuppressed(error) else throw error
    end try
    observer.endRun(completed)
    observer.flush()
    observer.close()
    if observer.displaysResults then save()
    completed

  end execute

  /** Failures that end the run even when the run continues past failing benchmarks. */
  private[runtime] def mustPropagate(error: Throwable, failOnError: Boolean): Boolean =
    failOnError || error.isInstanceOf[TrialObserverFailure] || error.isInstanceOf[UnfinishedWorkersException] ||
      ForkOutputFailure.failed || Platform.isCallerInterrupted

end BenchmarkRunner
