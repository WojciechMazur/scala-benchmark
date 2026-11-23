package microbench.runtime

import org.junit.Assert.*
import org.junit.Test

class TrialObserverTest:

  @Test def sharedExecutionEventsPreserveLifecycleAndFailurePrefixes(): Unit =
    assertEquals(32, TrialObserverChecks.verify())

  @Test def observerTimeDoesNotConsumeWorkerBarrierTimeout(): Unit =
    import org.openjdk.jmh.annotations.Mode
    val suite = new Suite:
      def metadata(): Array[BenchmarkMetadata] = Array.empty
      def instantiate(id: Int): AnyRef = new Object
      def assign(id: Int, target: AnyRef, parameter: String, value: String): Unit = ()
      def setup(id: Int, level: Int, target: AnyRef, env: Environment): Unit = ()
      def teardown(id: Int, level: Int, target: AnyRef, env: Environment): Unit = ()
      def measure(id: Int, env: Environment, control: IterationControl): RawResult = RawResult(1, 1, new SampleBuffer)
    val config = Configuration(warmupIterations = 1, measurementIterations = 2, threads = 2, timeoutNanos = 500000000L)
    val descriptor = Descriptor(
      0,
      "observer.slow",
      Vector(Mode.SingleShotTime),
      config,
      Vector(StateDefinition(0, false, Vector.empty, Vector.empty)),
      Vector.empty
    )
    val observer = new TrialObserver:
      def iteration(warmup: Boolean, index: Int): Unit = Thread.sleep(600)
      def iterationResult(warmup: Boolean, index: Int, result: IterationResult): Unit = Thread.sleep(600)
    assertEquals(
      2,
      Execution.trial(suite, descriptor, Mode.SingleShotTime, config, Map.empty, observer = observer).size
    )

  @Test def cancellingAnObserverPreservesInterruptionAndReapsWorkers(): Unit =
    import java.util.concurrent.{CountDownLatch, TimeUnit}
    import java.util.concurrent.atomic.AtomicReference
    import org.openjdk.jmh.annotations.Mode
    import scala.jdk.CollectionConverters.*
    for event <- Vector("start:1", "result:1", "start:2", "result:2"); wrapped <- Vector(false, true) do
      val entered = new CountDownLatch(1)
      val never = new CountDownLatch(1)
      val workers = new java.util.concurrent.ConcurrentLinkedQueue[Thread]
      val failure = new AtomicReference[Throwable]
      val restored = new java.util.concurrent.atomic.AtomicBoolean
      val suite = new Suite:
        def metadata(): Array[BenchmarkMetadata] = Array.empty
        def instantiate(id: Int): AnyRef =
          workers.add(Thread.currentThread()); new Object
        def assign(id: Int, target: AnyRef, parameter: String, value: String): Unit = ()
        def setup(id: Int, level: Int, target: AnyRef, env: Environment): Unit = ()
        def teardown(id: Int, level: Int, target: AnyRef, env: Environment): Unit = ()
        def measure(id: Int, env: Environment, control: IterationControl): RawResult = RawResult(1, 1, new SampleBuffer)
      val c = Configuration(warmupIterations = 0, measurementIterations = 2, threads = 2, timeoutNanos = 2000000000L)
      val d = Descriptor(
        0,
        "observer.cancel",
        Vector(Mode.SingleShotTime),
        c,
        Vector(StateDefinition(0, false, Vector.empty, Vector.empty)),
        Vector.empty
      )
      val observer = new TrialObserver:
        private def block(at: String): Unit = if event == at then
          entered.countDown()
          try never.await()
          catch
            case error: InterruptedException if wrapped =>
              Thread.currentThread().interrupt()
              throw new TrialObserverFailure(error)
        def iteration(warmup: Boolean, index: Int): Unit = block("start:" + index)
        def iterationResult(warmup: Boolean, index: Int, result: IterationResult): Unit = block("result:" + index)
      val coordinator = new Thread(() =>
        try Execution.trial(suite, d, Mode.SingleShotTime, c, Map.empty, observer = observer)
        catch case error: Throwable => failure.set(error)
        finally restored.set(Thread.currentThread().isInterrupted())
        ()
      )
      coordinator.setDaemon(true)
      coordinator.start()
      try
        assertTrue(event, entered.await(5, TimeUnit.SECONDS))
        coordinator.interrupt()
        coordinator.join(5000)
        assertFalse(event, coordinator.isAlive)
        assertTrue(
          event + ": interruption",
          if wrapped then failure.get().isInstanceOf[TrialObserverFailure]
          else failure.get().isInstanceOf[InterruptedException]
        )
        assertTrue(event + ": interrupt flag", restored.get())
        assertTrue(event + ": worker leak", workers.asScala.forall(!_.isAlive))
      finally
        never.countDown()
        coordinator.interrupt()
        coordinator.join(5000)
    end for

  end cancellingAnObserverPreservesInterruptionAndReapsWorkers

end TrialObserverTest
