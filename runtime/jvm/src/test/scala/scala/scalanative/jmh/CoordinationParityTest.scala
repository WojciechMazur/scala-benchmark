package scala.scalanative.jmh

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.openjdk.jmh.annotations.Mode

class CoordinationParityTest {
  private class ProbeSuite extends Suite {
    val setupEntered = new CountDownLatch(1)
    val releaseSetup = new CountDownLatch(1)
    val fastWork = new CountDownLatch(1)
    def metadata(): Array[String] = Array.empty
    def instantiate(id: Int): AnyRef = new Object
    def assign(id: Int, target: AnyRef, parameter: String, value: String): Unit = ()
    def setup(id: Int, level: Int, target: AnyRef, env: Environment): Unit = {
      if (level == 1 && env.threadParams.getThreadIndex() == 1) {
        setupEntered.countDown()
        require(releaseSetup.await(5, TimeUnit.SECONDS), "Test did not release slow setup")
      }
    }
    def teardown(id: Int, level: Int, target: AnyRef, env: Environment): Unit = ()
    def measure(id: Int, env: Environment, control: IterationControl): RawResult =
      Loops.run(
        control,
        env.sampling,
        false,
        { if (env.threadParams.getThreadIndex() == 0) fastWork.countDown() },
        (),
        (),
        env.notifyControl
      )
  }

  @Test def fastWorkerCanCatchUpWhilePeerRunsIterationSetup(): Unit = {
    for (sync <- Vector(true, false)) {
      val expected = UpstreamHarness(UpstreamHarness.Fixtures(false, false)).concurrentCatchup(sync)
      assertTrue("Reference did not observe fast worker progress", expected)
      val suite = new ProbeSuite
      val config = Configuration(
        warmupIterations = 0,
        measurementIterations = 1,
        measurementNanos = 1000000000L,
        threads = 2,
        timeoutNanos = 5000000000L,
        synchIterations = sync
      )
      val descriptor = Descriptor(
        0,
        "coordination",
        Vector(Mode.Throughput),
        config,
        Vector(StateDefinition(0, false, Vector.empty, Vector.empty)),
        Vector.empty
      )
      val failure = new AtomicReference[Throwable]
      val coordinator = new Thread(
        new Runnable {
          def run(): Unit = try { Execution.trial(suite, descriptor, Mode.Throughput, config, Map.empty); () }
          catch { case error: Throwable => failure.set(error) }
        },
        "portable-coordinator-probe"
      )
      coordinator.setDaemon(true)
      coordinator.start()
      var observed = false
      try {
        assertTrue(suite.setupEntered.await(5, TimeUnit.SECONDS))
        observed = suite.fastWork.await(500, TimeUnit.MILLISECONDS)
      } finally {
        suite.releaseSetup.countDown()
        coordinator.join(10000)
      }
      assertFalse("Coordinator did not terminate", coordinator.isAlive)
      if (failure.get() != null) throw failure.get()
      assertEquals(s"sync=$sync: progress before peer finishes setup", expected, observed)
    }
  }
}
