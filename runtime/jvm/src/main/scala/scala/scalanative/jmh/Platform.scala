package scala.scalanative.jmh

/** Only used by deterministic harness tests. JVM benchmarks use upstream JMH.
  */
trait PlatformSuite

class ControlFields {
  @volatile var done: Boolean = false
  @volatile var failure: Throwable = null
  val started = new java.util.concurrent.atomic.AtomicInteger
  val finished = new java.util.concurrent.atomic.AtomicInteger
}

object Platform {
  def loadSuite(name: String): Suite =
    Class.forName(name).getField("MODULE$").get(null).asInstanceOf[Suite]
  @volatile private var scalar = 0L
  @volatile private var reference: AnyRef = null
  def consume(value: Long): Unit = scalar = value
  def consumeReference(value: AnyRef): Unit = reference = value
  def executable: String = throw new UnsupportedOperationException(
    "Use upstream JMH on JVM"
  )
  def description: String = "JVM harness tests"
}
