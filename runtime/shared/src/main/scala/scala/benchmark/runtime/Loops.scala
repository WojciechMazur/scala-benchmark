/*
 * Copyright (c) 2005, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package scala.benchmark.runtime

import org.openjdk.jmh.annotations.Mode

final class IterationControl(
    val mode: Mode,
    val batchSize: Int,
    val operationsPerInvocation: Int,
    val durationNanos: Long
) extends ControlFields:
  var threadCount: Int = 1
  var synchIterations: Boolean = true

final case class RawResult(
    invocations: Long,
    elapsedNanos: Long,
    samples: SampleBuffer,
    auxiliary: Vector[AuxiliaryValue] = Vector.empty,
    allInvocations: Long = -1L
)

final class SamplingState:
  var mask: Int = 0

/** These templates are expanded by Scala's inliner into each generated harness. No function object or virtual
  * invocation is introduced into the measured loop. Counting and batching follow JMH 1.37 BenchmarkGenerator.
  */
object Loops:

  inline def run(
      control: IterationControl,
      sampling: SamplingState,
      inline hasInvocationFixtures: Boolean,
      inline invoke: Unit,
      inline setup: Unit,
      inline teardown: Unit,
      notifyControl: org.openjdk.jmh.infra.Control = new org.openjdk.jmh.infra.Control
  ): RawResult =
    runWithClock(control, sampling, hasInvocationFixtures, invoke, setup, teardown, notifyControl, System.nanoTime())

  /** Test clock expressions are inlined; ordinary harnesses retain direct System.nanoTime calls. */
  private[runtime] inline def runWithClock(
      control: IterationControl,
      sampling: SamplingState,
      inline hasInvocationFixtures: Boolean,
      inline invoke: Unit,
      inline setup: Unit,
      inline teardown: Unit,
      notifyControl: org.openjdk.jmh.infra.Control,
      inline nanoTime: Long
  ): RawResult =
    var operations = 0L
    var extraInvocations = 0L
    var realTime = 0L
    val samples = new SampleBuffer
    val batchSize = control.batchSize
    if control.synchIterations && control.mode != Mode.SingleShotTime then
      if control.started.incrementAndGet() == control.threadCount then control.announceReady()
      while
        control.started
          .get() < control.threadCount && control.failure == null
      do
        if hasInvocationFixtures then setup
        invoke
        extraInvocations += 1
        if hasInvocationFixtures then teardown
    notifyControl.startMeasurement = true
    val result = if control.mode == Mode.SingleShotTime then
      val start = nanoTime
      var b = 0
      while b < batchSize && !control.done do
        if hasInvocationFixtures then setup
        val invocationStart =
          if hasInvocationFixtures then nanoTime else 0L
        invoke
        if hasInvocationFixtures then
          realTime += nanoTime - invocationStart
          teardown
        operations += 1
        b += 1
      val elapsed = nanoTime - start
      RawResult(
        operations,
        if hasInvocationFixtures && realTime > 0 then realTime else elapsed,
        samples
      )
    else if control.mode == Mode.SampleTime then
      var random = nanoTime.toInt
      var mask = sampling.mask
      var stride = 0
      val target = (control.durationNanos / 1000000L * 20).toInt
      val start = nanoTime
      var running = true
      while running do
        if hasInvocationFixtures then setup
        val invocationStart = if hasInvocationFixtures then nanoTime else 0L
        random = random * 1664525 + 1013904223
        val sample = (random & mask) == 0
        val sampleStart = if sample then nanoTime else 0L
        var b = 0
        while b < batchSize do
          invoke; b += 1
        if sample then
          samples.add((nanoTime - sampleStart) / control.operationsPerInvocation)
          val previousStride = stride
          stride += 1
          if previousStride > target then
            samples.half()
            stride = 0
            mask = (mask << 1) | 1
        if hasInvocationFixtures then
          realTime += nanoTime - invocationStart; teardown
        operations += 1
        running = !control.done
      // JMH 1.37 passes startRndMask into the static stub by value. Adaptation
      // is local to this iteration and does not update the harness field.
      RawResult(
        operations * batchSize,
        if hasInvocationFixtures && realTime > 0 then realTime else nanoTime - start,
        samples
      )
    else
      val start = nanoTime
      var running = true
      while running do
        if hasInvocationFixtures then setup
        val invocationStart =
          if hasInvocationFixtures then nanoTime else 0L
        invoke
        if hasInvocationFixtures then
          realTime += nanoTime - invocationStart
          teardown
        operations += 1
        running = !control.done
      RawResult(
        operations,
        if hasInvocationFixtures && realTime > 0 then realTime else nanoTime - start,
        samples
      )
    notifyControl.stopMeasurement = true
    if control.synchIterations && control.mode != Mode.SingleShotTime then
      control.finished.incrementAndGet()
      while
        control.finished
          .get() < control.threadCount && control.failure == null
      do
        if hasInvocationFixtures then setup
        invoke
        extraInvocations += 1
        if hasInvocationFixtures then teardown
    result.copy(allInvocations = result.invocations + extraInvocations)

  end runWithClock

end Loops
