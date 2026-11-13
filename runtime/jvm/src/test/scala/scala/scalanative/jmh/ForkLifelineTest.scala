package scala.scalanative.jmh

import java.io.{PipedInputStream, PipedOutputStream}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.junit.Assert.*
import org.junit.Test

class ForkLifelineTest {
  @Test(timeout = 5000L) def ownerPipeClosureNotifiesGuard(): Unit = {
    val input = new PipedInputStream
    val owner = new PipedOutputStream(input)
    val lost = new CountDownLatch(1)
    val guard = ForkLifeline.start(input, () => lost.countDown())
    try {
      assertTrue(guard.isDaemon)
      assertFalse("A live owner was treated as dead", lost.await(50, TimeUnit.MILLISECONDS))
      owner.close()
      assertTrue("EOF was not observed", lost.await(2, TimeUnit.SECONDS))
      guard.join(1000)
      assertFalse(guard.isAlive)
    } finally { owner.close(); input.close() }
  }
}
