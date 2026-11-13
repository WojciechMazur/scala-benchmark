package scala.benchmark.runtime

import scala.scalajs.js

import org.openjdk.jmh.annotations.Mode

object Main:

  /** Entry point of a benchmark project, called by its generated main class. */
  def run(args: Array[String], suites: Seq[(String, Suite)], buildInfo: Seq[(String, String)]): Unit =
    Registry.install(suites, buildInfo)
    main(args)

  def main(args: Array[String]): Unit =
    try
      val arguments = Host.arguments.toArray
      if arguments.headOption.contains("--benchmark-worker-v3") then
        require(arguments.length == 2, "Invalid JS worker request")
        val request = Host.parse(arguments(1))
        if request.stdio.asInstanceOf[js.UndefOr[Boolean]].getOrElse(false) then
          val framework = Platform.workerStream(3)
          try
            BenchmarkOutput.worker(
              Platform.workerStream(1),
              Platform.workerStream(2),
              BenchmarkOutput.Streams(framework, framework)
            )(worker(request))
          finally framework.close()
        else worker(request)
      else BenchmarkRunner.run(Options.parse(arguments))
    catch
      case t: Throwable =>
        if ForkOutputFailure.failed then Host.exit(1)
        System.err.println(s"Scala.js benchmark failed: ${t.getMessage}")
        t.printStackTrace()
        Host.exit(1)

  private def worker(request: js.Dynamic): Unit =
    val suite = BenchmarkOutput.user(Platform.loadSuite(request.suite.asInstanceOf[String]))
    val index = request.index.asInstanceOf[Int]
    val mode = Mode.valueOf(request.mode.asInstanceOf[String])
    val descriptor = Descriptor.of(index, suite.metadata()(index), Some(mode))
    val options = Options(overrides = request.overrides.asInstanceOf[js.Dictionary[String]].toMap)
    val parameters = request.parameters.asInstanceOf[js.Dictionary[String]].toMap
    var sequence = 0L
    val progress: (Int, Long) => Unit = (_, budget) =>
      Host.progress(ForkProgress.message(sequence, math.max(1L, budget)))
      sequence += 1
    val warmups = request.warmups.asInstanceOf[js.Array[js.Dynamic]].toVector.map { warmup =>
      val name = warmup.suite.asInstanceOf[String]
      val suite = BenchmarkOutput.user(Platform.loadSuite(name))
      val index = warmup.index.asInstanceOf[Int]
      val mode = Mode.valueOf(warmup.mode.asInstanceOf[String])
      val descriptor = Descriptor.of(index, suite.metadata()(index), Some(mode))
      val config = Options(overrides = warmup.overrides.asInstanceOf[js.Dictionary[String]].toMap)
        .configuration(descriptor.configuration)
      WarmupRequest(name, index, mode, config, warmup.parameters.asInstanceOf[js.Dictionary[String]].toMap)
    }
    val observer: RunObserver =
      if !request.events.asInstanceOf[js.UndefOr[Boolean]].getOrElse(false) then
        RunObserver.Disabled
      else new ForkEvents.Recorder((sequence, text) => Host.event(sequence, text))
    try
      WarmupRequest.execute(
        warmups,
        progress,
        request.failOnError.asInstanceOf[js.UndefOr[Boolean]].getOrElse(true),
        observer
      )
      val config = options.configuration(descriptor.configuration)
      progress(-1, ForkProgress.budget(math.max(config.warmupNanos, config.measurementNanos), config.timeoutNanos))
      val results = try
        Execution.trial(
          suite,
          descriptor,
          mode,
          config,
          parameters,
          progress,
          retainCompletedOnFailure = true,
          observer = observer.trial(descriptor, mode, config, parameters)
        )
      catch
        case error: TrialObserverFailure => throw error
        case error: Throwable            =>
          error.printStackTrace(Console.err)
          Host.complete(Platform.encodeFailure(error))
          return
      Host.complete(Platform.encode(results))
    finally
      observer.flush(); observer.close()

  end worker

end Main
