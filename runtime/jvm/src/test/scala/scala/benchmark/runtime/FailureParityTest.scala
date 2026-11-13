package scala.benchmark.runtime

import org.junit.Assert.*
import org.junit.Test
import org.openjdk.jmh.annotations.Mode

class FailureParityTest:

  @Test def userCleanupAfterFailureMatchesGeneratedJmh(): Unit =
    val reference = UpstreamHarness(UpstreamHarness.Fixtures(true, true))
    for
      stage <- Vector(
        "",
        "construct",
        "trialSetup",
        "iterationSetup",
        "invocationSetup",
        "invoke",
        "invocationTeardown",
        "iterationTeardown",
        "trialTeardown"
      )
    do
      reference.synchronized {
        val expected = reference.failureTrace(stage)
        reference.prepareFailure(stage)
        def record(event: String): Unit = try reference.record(event)
        catch case error: java.lang.reflect.InvocationTargetException => throw error.getCause
        val suite = new Suite:
          def metadata(): Array[BenchmarkMetadata] = Array.empty
          def instantiate(id: Int): AnyRef =
            record("construct"); new Object
          def assign(id: Int, target: AnyRef, parameter: String, value: String): Unit = ()
          def setup(id: Int, level: Int, target: AnyRef, env: Environment): Unit =
            record(if level == 0 then "trialSetup" else "iterationSetup")
          def teardown(id: Int, level: Int, target: AnyRef, env: Environment): Unit =
            record(if level == 0 then "trialTeardown" else "iterationTeardown")
          def measure(id: Int, env: Environment, control: IterationControl): RawResult =
            Loops.run(
              control,
              env.sampling,
              true,
              record("invoke"),
              record("invocationSetup"),
              record("invocationTeardown"),
              env.notifyControl
            )
        val config = Configuration(warmupIterations = 0, measurementIterations = 1, timeoutNanos = 5000000000L)
        val descriptor = Descriptor(
          0,
          "failure",
          Vector(Mode.SingleShotTime),
          config,
          Vector(StateDefinition(0, false, Vector.empty, Vector.empty)),
          Vector.empty
        )
        val failure = try
          Execution.trial(suite, descriptor, Mode.SingleShotTime, config, Map.empty); None
        catch case error: Throwable => Some(error.getMessage)
        assertEquals(stage + ": primary failure", expected._2, failure)
        assertEquals(stage + ": lifecycle trace", expected._1, reference.events)
      }
    end for

  end userCleanupAfterFailureMatchesGeneratedJmh

  @Test(timeout = 30000L) def workerFailuresReleasePeersAtEveryLifecycleBoundary(): Unit =
    import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
    import scala.jdk.CollectionConverters.*
    for
      sync <- Vector(true, false); mode <- Vector(Mode.Throughput, Mode.SingleShotTime);
      warmup <- Vector(0, 1);
      stage <- Vector("construct", "trialSetup", "iterationSetup", "measure", "iterationTeardown", "trialTeardown")
    do
      val peer = new CountDownLatch(1)
      val threads = new ConcurrentLinkedQueue[Thread]
      def phase(name: String, index: Int): Unit = if name == stage then
        if index == 1 then peer.countDown()
        else
          require(peer.await(1, TimeUnit.SECONDS), s"Peer did not reach $stage")
          throw new IllegalStateException(stage)
      val suite = new Suite:
        def metadata(): Array[BenchmarkMetadata] = Array.empty
        def instantiate(id: Int): AnyRef =
          val thread = Thread.currentThread()
          threads.add(thread)
          phase("construct", if thread.getName.endsWith("-0") then 0 else 1)
          new Object
        def assign(id: Int, target: AnyRef, parameter: String, value: String): Unit = ()
        def setup(id: Int, level: Int, target: AnyRef, env: Environment): Unit =
          phase(if level == 0 then "trialSetup" else "iterationSetup", env.threadParams.getThreadIndex())
        def teardown(id: Int, level: Int, target: AnyRef, env: Environment): Unit =
          phase(if level == 0 then "trialTeardown" else "iterationTeardown", env.threadParams.getThreadIndex())
        def measure(id: Int, env: Environment, control: IterationControl): RawResult =
          phase("measure", env.threadParams.getThreadIndex())
          Loops.run(control, env.sampling, false, (), (), (), env.notifyControl)
      val config = Configuration(
        warmupIterations = warmup,
        measurementIterations = 1,
        warmupNanos = 1000000L,
        measurementNanos = 1000000L,
        threads = 2,
        timeoutNanos = 1000000000L,
        synchIterations = sync
      )
      val descriptor = Descriptor(
        0,
        "worker-failure",
        Vector(mode),
        config,
        Vector(StateDefinition(0, false, Vector.empty, Vector.empty)),
        Vector.empty
      )
      val failure = scala.util.Try(Execution.trial(suite, descriptor, mode, config, Map.empty)).failed.get
      assertEquals(s"mode=$mode sync=$sync warmup=$warmup", stage, failure.getMessage)
      assertTrue("A failed trial left a worker running", threads.asScala.forall(!_.isAlive))
    end for

  end workerFailuresReleasePeersAtEveryLifecycleBoundary

end FailureParityTest
