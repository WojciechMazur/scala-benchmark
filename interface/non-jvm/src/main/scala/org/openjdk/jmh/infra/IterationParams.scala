package org.openjdk.jmh.infra

import org.openjdk.jmh.runner.IterationType
import org.openjdk.jmh.runner.options.TimeValue

final class IterationParams(
    private val kind: IterationType,
    private val count: Int,
    private val time: TimeValue,
    private val batchSize: Int
) extends Serializable:
  def getType(): IterationType = kind
  def getCount(): Int = count
  def getTime(): TimeValue = time
  def getBatchSize(): Int = batchSize

  override def equals(other: Any): Boolean = other match
    case that: IterationParams =>
      kind == that.kind && count == that.count && time == that.time && batchSize == that.batchSize
    case _ => false

  override def hashCode(): Int = (kind, count, time, batchSize).hashCode()
  override def toString(): String = s"IterationParams($kind, $count, $time, $batchSize)"
