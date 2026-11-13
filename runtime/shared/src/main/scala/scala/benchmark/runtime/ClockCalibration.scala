package scala.benchmark.runtime

/** Resolution and cost of the monotonic clock, measured once per run and reported with the results. */
final case class ClockCalibration(
    minimumDeltaNanos: Long,
    averageCallNanos: Double
):
  def available: Boolean = minimumDeltaNanos > 0

object ClockCalibration:
  val Unavailable: ClockCalibration = ClockCalibration(0, Double.NaN)

  def measure(): ClockCalibration =
    var previous = System.nanoTime()
    val start = previous
    var minimum = Long.MaxValue
    var i = 0
    while i < 10000 do
      val now = System.nanoTime()
      require(now >= previous && now != 0, "Monotonic clock is unavailable")
      val delta = now - previous
      if delta > 0 then minimum = math.min(minimum, delta)
      previous = now
      i += 1
    require(minimum != Long.MaxValue, "Clock has no observable resolution")
    ClockCalibration(minimum, (previous - start) / 10000.0)
