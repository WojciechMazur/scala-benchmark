package scala.scalanative.jmh

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CyclicBarrier, TimeUnit}

import scala.collection.mutable

import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.infra.{Blackhole, ThreadParams}

final case class IterationResult(score: Double, workers: Vector[RawResult])

object Execution {
  def unitNanos(unit: String): Double =
    TimeUnit.valueOf(unit).toNanos(1).toDouble
  def aggregate(
      mode: Mode,
      configuration: Configuration,
      workers: Vector[RawResult]
  ): Double = {
    val scale = unitNanos(configuration.unit)
    val scores = workers.map { result =>
      val operations =
        if (mode == Mode.SingleShotTime)
          configuration.operationsPerInvocation.toDouble
        else
          result.invocations.toDouble * configuration.operationsPerInvocation / configuration.measurementBatchSize
      if (mode == Mode.Throughput) operations * scale / result.elapsedNanos
      else result.elapsedNanos / operations / scale
    }
    if (mode == Mode.Throughput) scores.sum else scores.sum / scores.size
  }

  /** All state construction and fixture calls execute on benchmark workers. The coordinator owns
    * the timer and never runs benchmark code.
    */
  def trial(
      suite: Suite,
      descriptor: Descriptor,
      mode: Mode,
      configuration: Configuration,
      parameters: Map[String, String],
      progress: (Int, Long) => Unit = (_, _) => ()
  ): Vector[IterationResult] = {
    val c = configuration
    require(c.threads > 0 && c.operationsPerInvocation > 0)
    require(c.measurementIterations > 0 && c.warmupIterations >= 0)
    require(c.warmupBatchSize > 0 && c.measurementBatchSize > 0)
    val phases = new CyclicBarrier(c.threads + 1)
    val current = new AtomicReference[IterationControl]
    val error = new AtomicReference[Throwable]
    val shared = mutable.Map.empty[Int, StateSlot]
    val results = new Array[RawResult](c.threads)
    val iterations = c.warmupIterations + c.measurementIterations
    def abort(t: Throwable): Unit = {
      error.compareAndSet(null, t)
      val control = current.get()
      if (control != null) { control.failure = t; control.done = true }
      phases.reset()
    }
    def waitPhase(): Unit = {
      val prior = error.get()
      if (prior != null) throw prior
      phases.await(c.timeoutNanos, TimeUnit.NANOSECONDS)
      val after = error.get()
      if (after != null) throw after
    }
    val workers = Vector.tabulate(c.threads) { index =>
      val thread = new Thread(
        new Runnable {
          def run(): Unit = {
            var env: Environment = null
            val initialized = mutable.ArrayBuffer.empty[Int]
            try {
              val storage =
                new Array[StateSlot](descriptor.states.map(_.id).max + 1)
              def construct(definition: StateDefinition): StateSlot = {
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
              }
              descriptor.states.foreach { definition =>
                storage(definition.id) =
                  if (definition.shared) shared.synchronized {
                    shared.getOrElseUpdate(definition.id, construct(definition))
                  }
                  else construct(definition)
              }
              env = new Environment(
                suite,
                storage,
                new Blackhole(Blackhole.Challenge),
                new ThreadParams(index, c.threads)
              )
              descriptor.states.foreach { state =>
                env.fixture(state.id, 0, true); initialized += state.id
              }
              var iteration = 0
              while (iteration < iterations && error.get() == null) {
                descriptor.states.foreach(state => env.fixture(state.id, 1, true))
                waitPhase() // all workers have initialized this iteration
                val control = current.get()
                waitPhase() // common start boundary
                try
                  results(index) = suite.measure(descriptor.index, env, control)
                finally {
                  // A failure releases peers spinning in generated catchup loops.
                  if (error.get() != null) control.failure = error.get()
                }
                waitPhase() // no iteration teardown while another worker measures
                descriptor.states.reverse.foreach(state => env.fixture(state.id, 1, false))
                waitPhase()
                iteration += 1
              }
            } catch { case t: Throwable => abort(t) }
            finally {
              if (env != null) initialized.reverseIterator.foreach { id =>
                try env.fixture(id, 0, false)
                catch {
                  case t: Throwable =>
                    if (!error.compareAndSet(null, t) && (error.get() ne t))
                      error.get().addSuppressed(t)
                }
              }
            }
          }
        },
        s"native-jmh-$index"
      )
      thread.setDaemon(true)
      thread
    }
    val measured = Vector.newBuilder[IterationResult]
    workers.foreach(_.start())
    try {
      var iteration = 0
      while (iteration < iterations) {
        val warmup = iteration < c.warmupIterations
        val duration = if (warmup) c.warmupNanos else c.measurementNanos
        val batch = if (warmup) c.warmupBatchSize else c.measurementBatchSize
        progress(iteration, duration + c.timeoutNanos)
        val control =
          new IterationControl(mode, batch, c.operationsPerInvocation, duration)
        control.threadCount = c.threads
        current.set(control)
        waitPhase()
        waitPhase()
        if (mode != Mode.SingleShotTime) {
          val start = System.nanoTime()
          var remaining = duration
          while (remaining > 0 && error.get() == null) {
            java.util.concurrent.locks.LockSupport
              .parkNanos(math.min(remaining, 10000000L))
            remaining = duration - (System.nanoTime() - start)
          }
          control.done = true
        }
        waitPhase()
        waitPhase()
        if (!warmup) {
          val raw = results.toVector
          require(
            raw.forall(r =>
              r != null && r.elapsedNanos >= 0 && r.invocations > 0 &&
                (mode == Mode.SingleShotTime || r.elapsedNanos > 0)
            ),
            "No measurable work or invalid clock interval"
          )
          measured += IterationResult(aggregate(mode, c, raw), raw)
        }
        iteration += 1
      }
    } catch { case t: Throwable => abort(t) }
    finally {
      if (error.get() != null) workers.foreach(_.interrupt())
      workers.foreach(_.join(math.max(1L, c.timeoutNanos / 1000000L)))
      if (workers.exists(_.isAlive))
        abort(
          new java.util.concurrent.TimeoutException(
            "Benchmark worker did not terminate"
          )
        )
    }
    if (error.get() != null) throw error.get()
    measured.result()
  }
}
