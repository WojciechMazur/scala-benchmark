package org.openjdk.jmh.infra

/** Deadline reads stay on the benchmark event loop, for both JS and Wasm. */
final class Control:
  var startMeasurement: Boolean = false
  private var stopped = false
  private[infra] var deadlineNanos: Long = 0L

  def stopMeasurement: Boolean =
    if !stopped && deadlineNanos != 0L && System.nanoTime() - deadlineNanos >= 0 then stopped = true
    stopped

  def stopMeasurement_=(value: Boolean): Unit = stopped = value

/** Internal host integration; absent from the portable benchmark-facing API. */
object ControlSupport:
  def deadline(control: Control, nanos: Long): Unit = control.deadlineNanos = nanos
