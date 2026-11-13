package scala.benchmark.runtime

/** Sequenced, complete progress records; time is measured only by the supervising process. */
private[runtime] object ForkProgress {
  val startupNanos: Long = 30000000000L
  def budget(duration: Long, timeout: Long): Long = {
    require(duration >= 0 && timeout >= 0, "Negative execution budget")
    if (duration > Long.MaxValue - timeout) Long.MaxValue else math.max(1L, duration + timeout)
  }
  def message(sequence: Long, budgetNanos: Long): String = {
    require(sequence >= 0 && budgetNanos > 0, "Invalid fork progress")
    s"3 $sequence $budgetNanos\n"
  }
  def parse(text: String): Option[(Long, Long)] = {
    if (!text.endsWith("\n")) None
    else {
      val parts = text.dropRight(1).split(" ", -1)
      def digits(value: String): Boolean = value.nonEmpty && value.forall(c => c >= '0' && c <= '9')
      if (parts.length != 3 || parts(0) != "3" || !digits(parts(1)) || !digits(parts(2))) None
      else
        try {
          val sequence = parts(1).toLong
          val budget = parts(2).toLong
          if (budget > 0) Some(sequence -> budget) else None
        } catch { case _: NumberFormatException => None }
    }
  }
}

private[runtime] final class ForkDeadline(startNanos: Long, initialBudgetNanos: Long) {
  require(initialBudgetNanos > 0, "Invalid startup budget")
  private var startedAt = startNanos
  private var budget = initialBudgetNanos
  private var sequence = -1L
  def receive(text: String, nowNanos: Long): Boolean = ForkProgress.parse(text) match {
    case Some((next, duration)) if next > sequence =>
      sequence = next; startedAt = nowNanos; budget = duration; true
    case _ => false
  }
  def expired(nowNanos: Long): Boolean = nowNanos - startedAt >= budget
}
