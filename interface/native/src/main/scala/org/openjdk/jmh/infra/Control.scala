package org.openjdk.jmh.infra

/** Flags shared by workers for one iteration, including warmup iterations. */
final class Control {
  @volatile var startMeasurement: Boolean = false
  @volatile var stopMeasurement: Boolean = false
}
