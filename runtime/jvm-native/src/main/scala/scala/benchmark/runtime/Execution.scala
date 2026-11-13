package scala.benchmark.runtime

import java.util.concurrent.{CyclicBarrier, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

import scala.collection.mutable

import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.infra.{Blackhole, ThreadParams}

object Execution extends MeasurementMath:

  /** All state construction and fixture calls execute on benchmark workers. The coordinator owns the timer and never
    * runs benchmark code.
    */
  def trial(
      suite: Suite,
      descriptor: Descriptor,
      mode: Mode,
      configuration: Configuration,
      parameters: Map[String, String],
      progress: (Int, Long) => Unit = (_, _) => (),
      retainCompletedOnFailure: Boolean = false,
      observer: TrialObserver = TrialObserver.Disabled
  ): Vector[IterationResult] =
    WorkerContainment.check()
    val coordinator = Thread.currentThread()
    var callerInterrupted = false
    def interruptible[A](body: => A): A = try body
    catch
      case error: InterruptedException =>
        if Thread.currentThread() eq coordinator then callerInterrupted = true
        throw error
    val d = descriptor.configured(configuration)
    val resolved = d.resolve(configuration)
    val c = if mode == Mode.SingleShotTime then resolved.copy(synchIterations = false) else resolved
    require(c.threads > 0 && c.operationsPerInvocation > 0)
    require(c.measurementIterations >= 0 && c.warmupIterations >= 0 && c.measurementIterations + c.warmupIterations > 0)
    require(c.warmupBatchSize > 0 && c.measurementBatchSize > 0)
    val trialStart = System.currentTimeMillis()
    var measurementStart = if c.warmupIterations == 0 then trialStart else 0L
    var warmupOperations = 0L
    var measurementOperations = 0L
    val benchmarkParams = Infrastructure.benchmark(d, mode, c, parameters)
    val phases = new CyclicBarrier(c.threads + 1)
    val preSetup = new CyclicBarrier(c.threads)
    def newControl(warmup: Boolean): IterationControl =
      val control = new IterationControl(
        mode,
        if warmup then c.warmupBatchSize else c.measurementBatchSize,
        c.operationsPerInvocation,
        if warmup then c.warmupNanos else c.measurementNanos
      )
      control.threadCount = c.threads
      control.synchIterations = c.synchIterations
      control
    val firstControl = newControl(c.warmupIterations > 0)
    if mode == Mode.SingleShotTime then firstControl.notifyControl.stopMeasurement = true
    val current = new AtomicReference[IterationControl](firstControl)
    val error = new AtomicReference[Throwable]
    val shared = mutable.Map.empty[(Int, Int), StateSlot]
    val results = new Array[RawResult](c.threads)
    val iterations = c.warmupIterations + c.measurementIterations
    val observing = observer ne TrialObserver.Disabled
    // Observer code must not consume workers' timed barrier budgets. Only the coordinator runs it.
    val observerGates =
      if observing then
        Vector.fill(iterations)(new java.util.concurrent.CountDownLatch(1))
      else Vector.empty
    def before(iteration: Int): Unit = if observing then
      val warmup = iteration < c.warmupIterations
      interruptible(
        TrialObserver.invoke(
          observer.iteration(warmup, if warmup then iteration + 1 else iteration - c.warmupIterations + 1)
        )
      )
    def after(iteration: Int, raw: Vector[RawResult]): Unit = if observing then
      val warmup = iteration < c.warmupIterations
      interruptible(
        TrialObserver.invoke(
          observer.iterationResult(
            warmup,
            if warmup then iteration + 1 else iteration - c.warmupIterations + 1,
            TrialObserver.result(mode, c, warmup, raw)
          )
        )
      )
    def abort(t: Throwable): Unit =
      error.compareAndSet(null, t)
      val control = current.get()
      if control != null then
        control.failure = t; control.done = true; control.notifyControl.stopMeasurement = true
        control.ready.countDown()
      phases.reset()
      preSetup.reset()
    def waitPhase(): Unit =
      val prior = error.get()
      if prior != null then throw prior
      interruptible(phases.await(c.timeoutNanos, TimeUnit.NANOSECONDS))
      val after = error.get()
      if after != null then throw after
    val workers = Vector.tabulate(c.threads) { index =>
      val thread = new Thread(
        new Runnable:
          def run(): Unit = BenchmarkOutput.user {
            var env: Environment = null
            val initialized = mutable.ArrayBuffer.empty[Int]
            try
              waitPhase() // release construction and first-trial fixtures after collection
              val threadParams = d.worker(index, c.threads)
              val definitions = d.workerStates(threadParams)
              val storage =
                new Array[StateSlot](d.states.map(_.id).max + 1)
              def construct(definition: StateDefinition): StateSlot =
                val instance = suite.instantiate(definition.id)
                definition.parameters.foreach(p =>
                  suite.assign(
                    definition.id,
                    instance,
                    p.name,
                    parameters(p.name)
                  )
                )
                new StateSlot(instance, definition.shared)
              definitions.foreach { definition =>
                storage(definition.id) =
                  if definition.shared then
                    shared.synchronized {
                      val group =
                        if definition.scope == org.openjdk.jmh.annotations.Scope.Group then threadParams.getGroupIndex()
                        else -1
                      val prior = error.get()
                      if prior != null then throw prior
                      try shared.getOrElseUpdate((definition.id, group), construct(definition))
                      catch
                        // Publish before releasing the construction lock: peers must
                        // not retry a failed shared constructor or parameter assignment.
                        case failure: Throwable => error.compareAndSet(null, failure); throw failure
                    }
                  else construct(definition)
              }
              env = new Environment(
                suite,
                storage,
                new Blackhole(
                  "Today's password is swordfish. I understand instantiating Blackholes directly is dangerous."
                ),
                threadParams,
                benchmarkParams,
                if c.warmupIterations > 0 then benchmarkParams.getWarmup() else benchmarkParams.getMeasurement()
              )
              env.notifyControl = firstControl.notifyControl
              definitions.foreach { state =>
                env.fixture(state.id, 0, true); initialized += state.id
              }
              var iteration = 0
              while iteration < iterations && error.get() == null do
                if iteration > 0 then
                  if observing then interruptible(observerGates(iteration).await())
                  waitPhase() // collection completes before iteration fixtures
                env.iterationParams =
                  if iteration < c.warmupIterations then benchmarkParams.getWarmup()
                  else benchmarkParams.getMeasurement()
                val control = current.get()
                env.notifyControl = control.notifyControl
                // JMH rendezvous before iteration setup, including unsynchronized iterations.
                interruptible(preSetup.await(c.timeoutNanos, TimeUnit.NANOSECONDS))
                definitions.foreach(state => env.fixture(state.id, 1, true))
                suite.resetAuxiliary(d.index, env)
                try
                  results(index) = suite.measure(d.index, env, control)
                finally
                  // A failure releases peers spinning in generated catchup loops.
                  if error.get() != null then control.failure = error.get()
                waitPhase() // no iteration teardown while another worker measures
                definitions.reverse.foreach(state => env.fixture(state.id, 1, false))
                // Last-iteration getters run once, after trial teardown.
                if iteration != iterations - 1 then results(index) = d.capture(suite, env, results(index))
                waitPhase()
                iteration += 1
            catch case t: Throwable => abort(t)
            finally
              // Generated JMH epilogues run only after successful measured/iteration work.
              // Infrastructure locks are still released by Environment's finally blocks.
              if env != null && error.get() == null then
                try initialized.reverseIterator.foreach(id => env.fixture(id, 0, false))
                catch case t: Throwable => abort(t)
              if env != null && error.get() == null && results(index) != null then
                try results(index) = d.capture(suite, env, results(index))
                catch case t: Throwable => abort(t)
            end try
          }
        ,
        s"benchmark-worker-$index"
      )
      thread.setDaemon(true)
      thread
    }
    val measured = Vector.newBuilder[IterationResult]
    var activeIteration = 0
    if c.doGC then Platform.requestGC()
    // This precedes worker startup, so even a slow first callback cannot expire their construction barrier.
    try before(0)
    finally if callerInterrupted then coordinator.interrupt()
    workers.foreach(_.start())
    try
      var iteration = 0
      while iteration < iterations do
        activeIteration = iteration
        if iteration > 0 then before(iteration)
        if observing then observerGates(iteration).countDown()
        if iteration == c.warmupIterations && iteration > 0 then measurementStart = System.currentTimeMillis()
        if c.doGC && iteration > 0 then Platform.requestGC()
        val warmup = iteration < c.warmupIterations
        val duration = if warmup then c.warmupNanos else c.measurementNanos
        val batch = if warmup then c.warmupBatchSize else c.measurementBatchSize
        progress(iteration, ForkProgress.budget(duration, c.timeoutNanos))
        val control = if iteration == 0 then firstControl else newControl(warmup)
        if mode == Mode.SingleShotTime then control.notifyControl.stopMeasurement = true
        current.set(control)
        waitPhase() // release iteration fixtures after collection
        if c.synchIterations then
          if !interruptible(control.ready.await(c.timeoutNanos, TimeUnit.NANOSECONDS)) then
            throw new java.util.concurrent.TimeoutException("Workers did not reach measurement readiness")
          val failure = error.get()
          if failure != null then throw failure
        if mode != Mode.SingleShotTime then
          val start = System.nanoTime()
          var remaining = duration
          while remaining > 0 && error.get() == null do
            java.util.concurrent.locks.LockSupport
              .parkNanos(math.min(remaining, 10000000L))
            if Thread.interrupted() then
              callerInterrupted = true
              throw new InterruptedException("Benchmark coordinator interrupted")
            remaining = duration - (System.nanoTime() - start)
          control.done = true
        control.notifyControl.stopMeasurement = true
        waitPhase()
        waitPhase()
        val observed = results.toVector
        if warmup then warmupOperations += operations(mode, c, observed, batch, all = true)
        else measurementOperations += operations(mode, c, observed, batch, all = true)
        if !warmup then
          val raw = observed
          require(
            raw.forall(r =>
              r != null && r.elapsedNanos >= 0 && r.invocations > 0 &&
                (mode == Mode.SingleShotTime || r.elapsedNanos > 0)
            ),
            "No measurable work or invalid clock interval"
          )
          measured += IterationResult(aggregate(mode, c, raw), raw)
        if iteration != iterations - 1 then after(iteration, observed)
        iteration += 1
      end while
    catch case t: Throwable => abort(t)
    finally
      // A callback may restore the interrupt flag before propagating its infrastructure failure.
      // Clear it while reaping workers, then restore it below; otherwise the first join would be skipped.
      if Thread.interrupted() then
        callerInterrupted = true
        abort(new InterruptedException("Benchmark coordinator interrupted"))
      if error.get() != null then workers.foreach(_.interrupt())
      val shutdownStart = System.nanoTime()
      val shutdownBudget = math.max(1000000L, c.timeoutNanos)
      workers.foreach { worker =>
        val remaining = shutdownBudget - (System.nanoTime() - shutdownStart)
        if remaining > 0 then
          try worker.join(remaining / 1000000L, (remaining % 1000000L).toInt)
          catch
            case interrupted: InterruptedException =>
              callerInterrupted = true
              abort(interrupted)
              workers.foreach(_.interrupt())
      }
      val survivors = workers.filter(_.isAlive)
      if survivors.nonEmpty then
        val failure = WorkerContainment.quarantine(survivors, error.get())
        error.set(failure)
        abort(failure)
      if callerInterrupted then coordinator.interrupt()
    end try
    if error.get() != null then
      if retainCompletedOnFailure then
        val prefix = measured.result().take(math.max(0, activeIteration - c.warmupIterations))
        error.get().addSuppressed(new CompletedIterations(prefix.map(_.copy(trial = None))))
      throw error.get()
    // Workers have completed trial teardown and the final auxiliary getters before this event.
    try after(iterations - 1, results.toVector)
    finally if callerInterrupted then coordinator.interrupt()
    val collected = measured.result()
    val finalized =
      if collected.nonEmpty && d.counters.nonEmpty then
        collected.updated(collected.size - 1, collected.last.copy(workers = results.toVector))
      else collected
    val metadata =
      TrialMetadata(trialStart, measurementStart, System.currentTimeMillis(), warmupOperations, measurementOperations)
    finalized.map(_.copy(trial = Some(metadata)))

  end trial

end Execution
