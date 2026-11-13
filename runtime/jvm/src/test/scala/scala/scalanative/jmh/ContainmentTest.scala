package scala.scalanative.jmh

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import org.junit.Assert.*
import org.junit.Test
import org.openjdk.jmh.annotations.Mode

class ContainmentTest {
  private class EmptySuite extends Suite {
    def metadata(): Array[String] = Array.empty
    def instantiate(id: Int): AnyRef = new Object
    def assign(id: Int, target: AnyRef, parameter: String, value: String): Unit = ()
    def setup(id: Int, level: Int, target: AnyRef, env: Environment): Unit = ()
    def teardown(id: Int, level: Int, target: AnyRef, env: Environment): Unit = ()
    def measure(id: Int, env: Environment, control: IterationControl): RawResult =
      Loops.run(control, env.sampling, false, (), (), (), env.notifyControl)
  }

  @Test(timeout = 15000L) def survivingWorkerPreventsAnotherEmbeddedMeasurement(): Unit = {
    val entries = Vector(
      classOf[ContainmentTest],
      classOf[Suite],
      classOf[scala.runtime.BoxedUnit],
      classOf[scala.deriving.Mirror],
      classOf[org.openjdk.jmh.annotations.Mode],
      classOf[org.junit.Assert],
      classOf[org.hamcrest.Matcher[?]],
      classOf[scala.benchmark.internal.BenchmarkInventory]
    )
      .map(_.getProtectionDomain.getCodeSource.getLocation.toURI)
      .map(java.nio.file.Paths.get(_).toString)
      .distinct
    val executable = java.nio.file.Paths.get(
      System.getProperty("java.home"),
      "bin",
      if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
    )
    val process = new ProcessBuilder(
      executable.toString,
      "-classpath",
      entries.mkString(java.io.File.pathSeparator),
      "scala.scalanative.jmh.ContainmentProbe"
    ).redirectErrorStream(true).start()
    try {
      assertTrue("Containment probe exceeded its outer watchdog", process.waitFor(10, TimeUnit.SECONDS))
      val output = new String(process.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      assertEquals(output, 0, process.exitValue())
    } finally if (process.isAlive) { process.destroyForcibly(); process.waitFor() }
  }

  @Test(timeout = 10000L) def callerInterruptionStopsCooperativeWorkersAndRestoresFlag(): Unit = {
    val entered = new CountDownLatch(1)
    val worker = new java.util.concurrent.atomic.AtomicReference[Thread]
    val failure = new java.util.concurrent.atomic.AtomicReference[Throwable]
    val restored = new AtomicBoolean
    val suite = new EmptySuite {
      override def measure(id: Int, env: Environment, control: IterationControl): RawResult = {
        worker.set(Thread.currentThread()); entered.countDown()
        super.measure(id, env, control)
      }
    }
    val config = Configuration(
      warmupIterations = 0,
      measurementIterations = 1,
      measurementNanos = 10000000000L,
      timeoutNanos = 1000000000L
    )
    val descriptor = Descriptor(
      0,
      "interruption",
      Vector(Mode.Throughput),
      config,
      Vector(StateDefinition(0, false, Vector.empty, Vector.empty)),
      Vector.empty
    )
    val coordinator = new Thread(
      new Runnable {
        def run(): Unit = {
          try { Execution.trial(suite, descriptor, Mode.Throughput, config, Map.empty); () }
          catch { case error: Throwable => failure.set(error) }
          finally restored.set(Thread.currentThread().isInterrupted)
        }
      },
      "interruption-probe"
    )
    coordinator.setDaemon(true)
    coordinator.start()
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS))
      coordinator.interrupt()
      coordinator.join(5000)
      assertFalse("Interrupted coordinator remained alive", coordinator.isAlive)
      assertTrue("Caller interruption was lost", failure.get().isInstanceOf[InterruptedException])
      assertTrue("Caller interrupt flag was not restored", restored.get())
      assertFalse("Cooperative worker remained alive", worker.get().isAlive)
    } finally {
      coordinator.interrupt()
      coordinator.join(2000)
    }
  }

  private[jmh] def runProbe(): Unit = {
    // This test alone intentionally leaves a worker alive until its assertions finish.
    // Serialize the quarantine probe with other trial tests through its own process.
    val release = new AtomicBoolean
    val peer = new CountDownLatch(1)
    val survivor = new java.util.concurrent.atomic.AtomicReference[Thread]
    val suite = new EmptySuite {
      override def measure(id: Int, env: Environment, control: IterationControl): RawResult = {
        if (env.threadParams.getThreadIndex() == 0) {
          require(peer.await(2, TimeUnit.SECONDS))
          throw new IllegalStateException("primary failure")
        }
        survivor.set(Thread.currentThread())
        peer.countDown()
        while (!release.get()) java.util.concurrent.locks.LockSupport.parkNanos(1000000L)
        RawResult(1, 1, new SampleBuffer)
      }
    }
    val config = Configuration(
      warmupIterations = 0,
      measurementIterations = 1,
      threads = 2,
      timeoutNanos = 100000000L,
      synchIterations = false
    )
    val descriptor = Descriptor(
      0,
      "containment",
      Vector(Mode.SingleShotTime),
      config,
      Vector(StateDefinition(0, false, Vector.empty, Vector.empty)),
      Vector.empty
    )
    try {
      val failure =
        scala.util.Try(Execution.trial(suite, descriptor, Mode.SingleShotTime, config, Map.empty)).failed.get
      assertTrue(failure.isInstanceOf[UnfinishedWorkersException])
      assertEquals("primary failure", failure.getCause.getMessage)
      val calls = new AtomicInteger
      val next = new EmptySuite { override def instantiate(id: Int): AnyRef = { calls.incrementAndGet(); new Object } }
      val blocked = scala.util.Try(Execution.trial(next, descriptor, Mode.SingleShotTime, config, Map.empty)).failed.get
      assertTrue(blocked.isInstanceOf[UnfinishedWorkersException])
      assertEquals("A contaminated process initialized another benchmark", 0, calls.get())
      assertTrue(
        "A quarantined parent could launch another measurement",
        scala.util.Try(Platform.ensureMeasurementHealthy()).failed.get.isInstanceOf[UnfinishedWorkersException]
      )
    } finally {
      release.set(true)
      if (survivor.get() != null) survivor.get().join(5000)
    }
    WorkerContainment.check()
  }
}

object ContainmentProbe {
  def main(args: Array[String]): Unit = new ContainmentTest().runProbe()
}
