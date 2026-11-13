package scala.benchmark.runtime

import org.junit.Assert.*
import org.junit.Test

class ForkProgressTest:

  @Test def staleAndPartialProgressCannotRenewDeadline(): Unit =
    val deadline = new ForkDeadline(100, 10)
    assertFalse(deadline.expired(109))
    assertTrue(deadline.expired(110))
    assertTrue(deadline.receive(ForkProgress.message(0, 20), 110))
    assertFalse(deadline.receive(ForkProgress.message(0, 1000), 129))
    assertTrue(deadline.expired(130))
    assertFalse(deadline.receive("3 1 100", 130))
    assertTrue(deadline.expired(131))
    assertTrue(deadline.receive("3 1 100\n", 131))
    assertFalse(deadline.expired(230))
    assertTrue(deadline.expired(231))

  @Test def malformedRecordsDoNotPoisonLaterProgress(): Unit =
    val invalid = Vector(
      "3 0 0\n",
      "3 -1 10\n",
      "3 0 -10\n",
      "2 0 10\n",
      "3 0 10\nextra",
      "3 9223372036854775808 1\n",
      "3 1 9223372036854775808\n",
      "3  1 10\n",
      "3 +1 10\n"
    )
    val deadline = new ForkDeadline(0, 10)
    invalid.foreach(text => assertFalse(text, deadline.receive(text, 5)))
    assertTrue(deadline.expired(10))
    assertTrue(deadline.receive("3 1 20\n", 10))
    assertFalse(deadline.expired(29))

  @Test def nanosecondWrapAndLargeBudgetsRemainExact(): Unit =
    val deadline = new ForkDeadline(Long.MaxValue - 10, 20)
    assertFalse(deadline.expired(Long.MinValue + 8))
    assertTrue(deadline.expired(Long.MinValue + 9))
    val large = 9007199254740993L
    assertEquals(Some(large -> large), ForkProgress.parse(ForkProgress.message(large, large)))
    assertEquals(Long.MaxValue, ForkProgress.budget(Long.MaxValue - 1, 10))
    assertEquals(1L, ForkProgress.budget(0, 0))

end ForkProgressTest
