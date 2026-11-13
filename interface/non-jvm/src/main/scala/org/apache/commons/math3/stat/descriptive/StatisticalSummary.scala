package org.apache.commons.math3.stat.descriptive

/** Summary contract exposed by the JMH statistics API. */
trait StatisticalSummary:
  def getMean(): Double
  def getVariance(): Double
  def getStandardDeviation(): Double
  def getMax(): Double
  def getMin(): Double
  def getN(): Long
  def getSum(): Double
