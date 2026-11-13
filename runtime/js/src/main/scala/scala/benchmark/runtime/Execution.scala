package scala.benchmark.runtime

import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.infra.Blackhole

object Execution extends MeasurementMath {
  def trial(
      suite: Suite,
      descriptor: Descriptor,
      mode: Mode,
      configuration: Configuration,
      parameters: Map[String, String],
      progress: (Int, Long) => Unit = (_, _) => (),
      retainCompletedOnFailure: Boolean = false,
      observer: TrialObserver = TrialObserver.Disabled
  ): Vector[IterationResult] = {
    val reporting = BenchmarkOutput.console
    val callbacks =
      if (observer eq TrialObserver.Disabled) observer
      else
        new TrialObserver {
          def iteration(warmup: Boolean, index: Int): Unit =
            BenchmarkOutput.withConsole(reporting)(observer.iteration(warmup, index))
          def iterationResult(warmup: Boolean, index: Int, result: IterationResult): Unit =
            BenchmarkOutput.withConsole(reporting)(observer.iterationResult(warmup, index, result))
        }
    BenchmarkOutput.user(
      execute(suite, descriptor, mode, configuration, parameters, progress, retainCompletedOnFailure, callbacks)
    )
  }
  private def execute(
      suite: Suite,
      descriptor: Descriptor,
      mode: Mode,
      configuration: Configuration,
      parameters: Map[String, String],
      progress: (Int, Long) => Unit,
      retainCompletedOnFailure: Boolean,
      observer: TrialObserver
  ): Vector[IterationResult] = {
    val d = descriptor.configured(configuration)
    val resolved = d.resolve(configuration)
    val c = if (mode == Mode.SingleShotTime) resolved.copy(synchIterations = false) else resolved
    Platform.validate(c)
    val trialStart = System.currentTimeMillis()
    var measurementStart = if (c.warmupIterations == 0) trialStart else 0L
    var warmupOperations = 0L
    var measurementOperations = 0L
    val observing = observer ne TrialObserver.Disabled
    def before(iteration: Int): Unit = if (observing) {
      val warmup = iteration < c.warmupIterations
      TrialObserver.invoke(
        observer.iteration(warmup, if (warmup) iteration + 1 else iteration - c.warmupIterations + 1)
      )
    }
    def after(iteration: Int, raw: RawResult): Unit = if (observing) {
      val warmup = iteration < c.warmupIterations
      TrialObserver.invoke(
        observer.iterationResult(
          warmup,
          if (warmup) iteration + 1 else iteration - c.warmupIterations + 1,
          TrialObserver.result(mode, c, warmup, Vector(raw))
        )
      )
    }
    before(0)
    if (c.doGC) Platform.requestGC()
    val firstControl = new IterationControl(
      mode,
      if (c.warmupIterations > 0) c.warmupBatchSize else c.measurementBatchSize,
      c.operationsPerInvocation,
      if (c.warmupIterations > 0) c.warmupNanos else c.measurementNanos
    )
    if (mode == Mode.SingleShotTime) firstControl.notifyControl.stopMeasurement = true
    val threadParams = d.worker(0, c.threads)
    val definitions = d.workerStates(threadParams)
    val storage = new Array[StateSlot](d.states.map(_.id).max + 1)
    def startDeadline(control: IterationControl, duration: Long): Unit = {
      control.deadline = System.nanoTime() + duration
      org.openjdk.jmh.infra.ControlSupport.deadline(control.notifyControl, control.deadline)
    }
    // With synchronization disabled, the host timer includes state/fixture startup.
    if (!c.synchIterations && mode != Mode.SingleShotTime) startDeadline(firstControl, firstControl.durationNanos)
    definitions.foreach { state =>
      val instance = suite.instantiate(state.id)
      state.parameters.foreach(p => suite.assign(state.id, instance, p.name, parameters(p.name)))
      storage(state.id) = new StateSlot(instance, state.shared)
    }
    val benchmarkParams = Infrastructure.benchmark(d, mode, c, parameters)
    val env = new Environment(
      suite,
      storage,
      new Blackhole(Blackhole.Challenge),
      threadParams,
      benchmarkParams,
      if (c.warmupIterations > 0) benchmarkParams.getWarmup() else benchmarkParams.getMeasurement()
    )
    env.notifyControl = firstControl.notifyControl
    val initialized = scala.collection.mutable.ArrayBuffer.empty[Int]
    var failure: Throwable = null
    val measured = Vector.newBuilder[IterationResult]
    var lastRaw: RawResult = null
    var activeIteration = 0
    try {
      definitions.foreach { state => env.fixture(state.id, 0, true); initialized += state.id }
      var iteration = 0
      while (iteration < c.warmupIterations + c.measurementIterations && failure == null) {
        activeIteration = iteration
        if (iteration > 0) before(iteration)
        if (iteration == c.warmupIterations && iteration > 0) measurementStart = System.currentTimeMillis()
        if (c.doGC && iteration > 0) Platform.requestGC()
        val warmup = iteration < c.warmupIterations
        env.iterationParams = if (warmup) benchmarkParams.getWarmup() else benchmarkParams.getMeasurement()
        val duration = if (warmup) c.warmupNanos else c.measurementNanos
        val batch = if (warmup) c.warmupBatchSize else c.measurementBatchSize
        progress(iteration, ForkProgress.budget(duration, c.timeoutNanos))
        val control =
          if (iteration == 0) firstControl else new IterationControl(mode, batch, c.operationsPerInvocation, duration)
        control.synchIterations = c.synchIterations
        env.notifyControl = control.notifyControl
        if (mode == Mode.SingleShotTime) control.notifyControl.stopMeasurement = true
        if (!c.synchIterations && mode != Mode.SingleShotTime && iteration > 0) startDeadline(control, duration)
        val active = scala.collection.mutable.ArrayBuffer.empty[Int]
        var raw: RawResult = null
        try {
          definitions.foreach { state => env.fixture(state.id, 1, true); active += state.id }
          suite.resetAuxiliary(d.index, env)
          val start = System.nanoTime()
          if (c.synchIterations && mode != Mode.SingleShotTime) startDeadline(control, duration)
          raw = suite.measure(d.index, env, control)
          if (System.nanoTime() - start > ForkProgress.budget(duration, c.timeoutNanos))
            throw new java.util.concurrent.TimeoutException("Benchmark iteration timed out")
          require(
            raw.invocations > 0 && raw.elapsedNanos >= 0 &&
              (mode == Mode.SingleShotTime || raw.elapsedNanos > 0),
            "No measurable work or invalid clock interval"
          )
          active.reverseIterator.foreach(id => env.fixture(id, 1, false))
        } catch { case t: Throwable => failure = t }
        if (failure == null) {
          lastRaw = raw
          val captured =
            if (iteration != c.warmupIterations + c.measurementIterations - 1) d.capture(suite, env, raw) else raw
          if (warmup) warmupOperations += operations(mode, c, Vector(raw), batch, all = true)
          else {
            measurementOperations += operations(mode, c, Vector(raw), batch, all = true)
            measured += IterationResult(aggregate(mode, c, Vector(raw)), Vector(captured))
          }
          if (iteration != c.warmupIterations + c.measurementIterations - 1) after(iteration, captured)
        }
        iteration += 1
      }
      // As in JMH's generated epilogue, a user failure skips later user fixtures.
      // Stop at the first teardown failure and preserve that primary exception.
      if (failure == null) initialized.reverseIterator.foreach(id => env.fixture(id, 0, false))
    } catch { case t: Throwable => failure = t }
    var lastCaptured: RawResult = null
    if (failure == null) try { if (lastRaw != null) lastCaptured = d.capture(suite, env, lastRaw) }
    catch { case error: Throwable => failure = error }
    if (failure != null) {
      if (retainCompletedOnFailure) {
        val prefix = measured.result().take(math.max(0, activeIteration - c.warmupIterations))
        failure.addSuppressed(new CompletedIterations(prefix.map(_.copy(trial = None))))
      }
      throw failure
    }
    if (lastCaptured != null) after(c.warmupIterations + c.measurementIterations - 1, lastCaptured)
    val collected = measured.result()
    val finalized = if (collected.nonEmpty && d.counters.nonEmpty) {
      val last = collected.last
      collected.updated(collected.size - 1, last.copy(workers = Vector(lastCaptured)))
    } else collected
    val metadata =
      TrialMetadata(trialStart, measurementStart, System.currentTimeMillis(), warmupOperations, measurementOperations)
    finalized.map(_.copy(trial = Some(metadata)))
  }
}
