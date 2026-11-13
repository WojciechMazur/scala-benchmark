package org.openjdk.jmh.util

class SampleBuffer extends scala.benchmark.internal.SamplingHistogram with Serializable {
  def addAll(other: SampleBuffer): Unit = merge(other)
  def getStatistics(multiplier: Double): Statistics = {
    val result = new MultisetStatistics
    entries.foreach { case (value, count) => result.addValue(value * multiplier, count) }
    result
  }
  def count(): Int = entries.map(_._2).sum.toInt
}
