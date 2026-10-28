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
package scala.scalanative.jmh

import org.openjdk.jmh.annotations.Mode

final class IterationControl(
    val mode: Mode,
    val batchSize: Int,
    val operationsPerInvocation: Int,
    val durationNanos: Long
) extends ControlFields {
  var threadCount: Int = 1
}

final case class RawResult(
    invocations: Long,
    elapsedNanos: Long,
    samples: SampleBuffer
)

final class SamplingState { var mask: Int = 0 }

/** These templates are expanded by Scala's inliner into each generated harness. No function object
  * or virtual invocation is introduced into the measured loop. Counting and batching follow JMH
  * 1.37 BenchmarkGenerator.
  */
object Loops {
  inline def run(
      control: IterationControl,
      sampling: SamplingState,
      inline hasInvocationFixtures: Boolean,
      inline invoke: Unit,
      inline setup: Unit,
      inline teardown: Unit
  ): RawResult = {
    var operations = 0L
    var realTime = 0L
    val samples = new SampleBuffer
    val batchSize = control.batchSize
    if (control.mode != Mode.SingleShotTime) {
      control.started.incrementAndGet()
      while (
        control.started
          .get() < control.threadCount && control.failure == null
      ) {
        if (hasInvocationFixtures) setup
        invoke
        if (hasInvocationFixtures) teardown
      }
    }
    val result = if (control.mode == Mode.SingleShotTime) {
      val start = System.nanoTime()
      var b = 0
      while (b < batchSize && !control.done) {
        if (hasInvocationFixtures) setup
        val invocationStart =
          if (hasInvocationFixtures) System.nanoTime() else 0L
        invoke
        if (hasInvocationFixtures) {
          realTime += System.nanoTime() - invocationStart
          teardown
        }
        operations += 1
        b += 1
      }
      val elapsed = System.nanoTime() - start
      RawResult(
        operations,
        if (hasInvocationFixtures) realTime else elapsed,
        samples
      )
    } else if (control.mode == Mode.SampleTime) {
      var random = System.nanoTime().toInt
      var mask = sampling.mask
      var stride = 0
      val target = math.max(1L, control.durationNanos / 50000L)
      val start = System.nanoTime()
      var running = true
      while (running) {
        if (hasInvocationFixtures) setup
        random = random * 1664525 + 1013904223
        val sample = (random & mask) == 0
        val invocationStart =
          if (sample || hasInvocationFixtures) System.nanoTime() else 0L
        var b = 0
        while (b < batchSize) { invoke; b += 1 }
        val elapsed =
          if (sample || hasInvocationFixtures)
            System.nanoTime() - invocationStart
          else 0L
        if (sample) {
          samples.add(elapsed / control.operationsPerInvocation)
          stride += 1
          if (stride > target) {
            samples.half()
            stride = 0
            mask = (mask << 1) | 1
          }
        }
        if (hasInvocationFixtures) { realTime += elapsed; teardown }
        operations += 1
        running = !control.done
      }
      sampling.mask = math.max(sampling.mask, mask)
      RawResult(
        operations * batchSize,
        if (hasInvocationFixtures) realTime else System.nanoTime() - start,
        samples
      )
    } else {
      val start = System.nanoTime()
      var running = true
      while (running) {
        if (hasInvocationFixtures) setup
        val invocationStart =
          if (hasInvocationFixtures) System.nanoTime() else 0L
        invoke
        if (hasInvocationFixtures) {
          realTime += System.nanoTime() - invocationStart
          teardown
        }
        operations += 1
        running = !control.done
      }
      RawResult(
        operations,
        if (hasInvocationFixtures) realTime else System.nanoTime() - start,
        samples
      )
    }
    if (control.mode != Mode.SingleShotTime) {
      control.finished.incrementAndGet()
      while (
        control.finished
          .get() < control.threadCount && control.failure == null
      ) {
        if (hasInvocationFixtures) setup
        invoke
        if (hasInvocationFixtures) teardown
      }
    }
    result
  }
}
