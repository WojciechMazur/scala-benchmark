package scala.benchmark.internal

/** Like JMH's JSON formatter, capture the property on first JSON formatter initialization. */
object JsonReportOptions {
  val rawData: Boolean = java.lang.Boolean.parseBoolean(System.getProperty("jmh.json.rawData", "true"))
  def initialize(): Unit = ()
}
