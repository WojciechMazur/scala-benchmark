package scala.benchmark.internal

object TimeServices {
  def sleep(time: Long, unit: java.util.concurrent.TimeUnit): Unit = unit.sleep(time)
}
