package scala.scalanative.jmh

/** Only used by deterministic harness tests. JVM benchmarks use upstream JMH. */
class ControlFields {
  val notifyControl = new org.openjdk.jmh.infra.Control
  @volatile var done: Boolean = false
  @volatile var failure: Throwable = null
  val ready = new java.util.concurrent.CountDownLatch(1)
  def announceReady(): Unit = ready.countDown()
  val started = new java.util.concurrent.atomic.AtomicInteger
  val finished = new java.util.concurrent.atomic.AtomicInteger
}

object Platform extends ProcessPlatform {
  def haltWorker(): Unit = Runtime.getRuntime.halt(1)
  def identity: scala.benchmark.Platform = scala.benchmark.Platform.JVM

  /** Tests may also name a hand-written suite object. */
  def loadSuite(name: String): Suite = Registry.suite(name).getOrElse {
    Class
      .forName(name, true, Thread.currentThread().getContextClassLoader)
      .getField("MODULE$")
      .get(null)
      .asInstanceOf[Suite]
  }
  def executable: String = throw new UnsupportedOperationException(
    "Use upstream JMH on JVM"
  )
  def description: String = "JVM harness tests"
}
