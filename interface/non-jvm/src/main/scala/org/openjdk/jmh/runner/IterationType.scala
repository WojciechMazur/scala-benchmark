package org.openjdk.jmh.runner

enum IterationType extends java.lang.Enum[IterationType] {
  case WARMUP, MEASUREMENT
}

object IterationType {
  @scala.annotation.targetName("javaValues")
  def values(): Array[IterationType] = Array(WARMUP, MEASUREMENT)
}
