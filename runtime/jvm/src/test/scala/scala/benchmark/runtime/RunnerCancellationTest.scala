package scala.benchmark.runtime

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}

import org.junit.Assert.*
import org.junit.Test

class RunnerCancellationTest:

  @Test def workerInterruptedExceptionRemainsARecoverableBenchmarkFailure(): Unit =
    val results = BenchmarkRunner.run(
      Options(suites = Vector("scala.benchmark.runtime.RunnerWorkerInterruptionSuite$"), failOnError = false)
    )
    assertEquals(1, results.size)
    assertEquals("B.recovery", results.head.descriptor.name)
    assertFalse(Thread.currentThread().isInterrupted)

  @Test(timeout = 10000L) def cancellationAbortsLaterConfigurationsDespiteContinueOnError(): Unit =
    val suite = RunnerCancellationSuite
    val failure = new AtomicReference[Throwable]
    val restored = new AtomicBoolean
    val caller = new Thread(new Runnable:
      def run(): Unit =
        try
          BenchmarkRunner.run(
            Options(suites = Vector("scala.benchmark.runtime.RunnerCancellationSuite$"), failOnError = false)
          )
        catch case error: Throwable => failure.set(error)
        finally restored.set(Thread.currentThread().isInterrupted))
    caller.setDaemon(true)
    try
      caller.start()
      assertTrue(suite.entered.await(3, TimeUnit.SECONDS))
      caller.interrupt()
      caller.join(5000)
      assertFalse("Cancelled runner remained alive", caller.isAlive)
      assertTrue(failure.get().isInstanceOf[InterruptedException])
      assertTrue("Runner lost caller interruption", restored.get())
      assertEquals("Runner executed a later configuration after cancellation", 0, suite.recovery.get())
    finally
      caller.interrupt(); caller.join(1000)

end RunnerCancellationTest

private[runtime] trait RunnerFailureSuite extends Suite:

  def metadata(): Array[BenchmarkMetadata] = Array("A.cancel", "B.recovery").map { name =>
    BenchmarkMetadata(
      name,
      Vector(org.openjdk.jmh.annotations.Mode.SingleShotTime),
      Vector(StateDefinition(0, shared = false, Vector.empty, Vector.empty)),
      DeclaredConfiguration(forks = Some(0), timeout = Some(org.openjdk.jmh.runner.options.TimeValue.seconds(1)))
    )
  }

  def instantiate(id: Int): AnyRef = new Object
  def assign(id: Int, target: AnyRef, parameter: String, value: String): Unit = ()
  def setup(id: Int, level: Int, target: AnyRef, env: Environment): Unit = ()
  def teardown(id: Int, level: Int, target: AnyRef, env: Environment): Unit = ()

object RunnerCancellationSuite extends RunnerFailureSuite:
  val entered = new CountDownLatch(1)
  val recovery = new AtomicInteger

  def measure(id: Int, env: Environment, control: IterationControl): RawResult =
    if id == 0 then
      entered.countDown(); Thread.sleep(10000)
    else recovery.incrementAndGet()
    RawResult(1, 1, new SampleBuffer)

object RunnerWorkerInterruptionSuite extends RunnerFailureSuite:

  def measure(id: Int, env: Environment, control: IterationControl): RawResult =
    if id == 0 then throw new InterruptedException("benchmark worker interruption")
    RawResult(1, 1, new SampleBuffer)
