package microbench.runtime

import org.junit.Assert.*
import org.junit.Test
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.infra.Control

class ExecutionParityTest:

  private def compare(
      mode: Mode,
      fixtures: UpstreamHarness.Fixtures,
      limit: Int,
      batch: Int,
      opi: Int,
      nanos: Long,
      work: Long,
      tick: Long,
      fixtureCost: Long,
      mask: Int = 0,
      seed: Long = 42
  ): Unit =
    val reference = UpstreamHarness(fixtures)
    reference.synchronized {
      reference.reset(limit, work, tick, fixtureCost, seed)
      val expected = reference.run(mode, batch, opi, nanos, mask)
      reference.reset(limit, work, tick, fixtureCost, seed)
      val control = new IterationControl(mode, batch, opi, nanos)
      control.synchIterations = false
      val sampling = new SamplingState
      sampling.mask = mask
      val result = Loops.runWithClock(
        control,
        sampling,
        fixtures.setup || fixtures.teardown,
        { reference.invoke(); if reference.calls >= limit then control.done = true },
        if fixtures.setup then reference.setup(),
        if fixtures.teardown then reference.teardown(),
        new Control,
        reference.read()
      )
      val actual =
        UpstreamHarness.Observation(reference.calls, reference.events, result.elapsedNanos, result.samples.entries)
      val id =
        s"${mode.shortLabel()}-calls-$limit-batch-$batch-opi-$opi-ns-$nanos-work-$work-tick-$tick-mask-$mask-seed-$seed"
      reference.evidence(id, expected, actual)
      assertEquals(id + ": calls", expected.calls, actual.calls)
      assertEquals(id + ": fixtures", expected.events, actual.events)
      if mode == Mode.SampleTime then
        assertEquals(id + ": histogram", expected.histogram, actual.histogram)
        if (fixtures.setup || fixtures.teardown) && work > 0 then
          assertEquals(id + ": fixture-adjusted sample interval", expected.elapsed, actual.elapsed)
      else assertEquals(id + ": timed region", expected.elapsed, actual.elapsed)
    }

  end compare

  @Test def completeGeneratedHarnessAgreesOnNormalization(): Unit =
    import java.util.concurrent.TimeUnit
    for
      mode <- Vector(Mode.Throughput, Mode.AverageTime, Mode.SingleShotTime, Mode.SampleTime);
      fixtures <- Vector(UpstreamHarness.Fixtures(false, false), UpstreamHarness.Fixtures(true, true));
      (batch, opi) <- Vector(1 -> 1, 7 -> 3, 3 -> 13);
      unit <- Vector(TimeUnit.NANOSECONDS, TimeUnit.MICROSECONDS, TimeUnit.SECONDS, TimeUnit.HOURS)
    do
      val reference = UpstreamHarness(fixtures)
      val limit = if mode == Mode.SingleShotTime then batch else 23
      reference.synchronized {
        reference.reset(limit, 11, 1, 100)
        val expected = reference.runFull(mode, batch, opi, 1000000L, unit)
        reference.reset(limit, 11, 1, 100)
        val control = new IterationControl(mode, batch, opi, 1000000L)
        control.synchIterations = false
        val raw = Loops.runWithClock(
          control,
          new SamplingState,
          fixtures.setup || fixtures.teardown,
          { reference.invoke(); if reference.calls >= limit then control.done = true },
          if fixtures.setup then reference.setup(),
          if fixtures.teardown then reference.teardown(),
          new Control,
          reference.read()
        )
        val config = Configuration(measurementBatchSize = batch, operationsPerInvocation = opi, unit = unit.name())
        val descriptor = Descriptor(0, "oracle.execute", Vector(mode), config, Vector.empty, Vector.empty)
        val result = RunResult(
          descriptor,
          mode,
          config,
          Map.empty,
          Vector(Vector(IterationResult(Execution.aggregate(mode, config, Vector(raw)), Vector(raw)))),
          ClockCalibration(1, 1)
        )
        val actual = UpstreamHarness.ResultObservation(
          reference.calls,
          reference.events,
          Execution.operations(mode, config, Vector(raw), batch, all = true),
          Execution.operations(mode, config, Vector(raw), batch, all = false),
          result.score,
          result.unit,
          result.statistics.values
        )
        val id = s"${mode.shortLabel()}-batch-$batch-opi-$opi-unit-$unit"
        reference.resultEvidence(id, expected, actual)
        assertEquals(id + ": calls", expected.calls, actual.calls)
        assertEquals(id + ": fixture trace", expected.events, actual.events)
        assertEquals(id + ": all operations", expected.allOps, actual.allOps)
        assertEquals(id + ": measured operations", expected.measuredOps, actual.measuredOps)
        if expected.score == 0 then assertEquals(id + ": score", 0.0, actual.score, 0.0)
        else assertTrue(id + ": score", math.abs((actual.score - expected.score) / expected.score) <= 1e-12)
        assertEquals(id + ": unit", expected.unit, actual.unit)
      }
    end for

  end completeGeneratedHarnessAgreesOnNormalization

  @Test def repeatedSampleIterationsUseTheReferenceInitialMask(): Unit =
    val reference = UpstreamHarness(UpstreamHarness.Fixtures(false, false))
    reference.synchronized {
      val session = reference.newSession()
      val sampling = new SamplingState
      for iteration <- 0 until 3 do
        reference.reset(200, 11, 0, 0)
        val expected =
          reference.runFull(Mode.SampleTime, 1, 1, 1000000L, java.util.concurrent.TimeUnit.NANOSECONDS, session)
        reference.reset(200, 11, 0, 0)
        val control = new IterationControl(Mode.SampleTime, 1, 1, 1000000L)
        control.synchIterations = false
        val raw = Loops.runWithClock(
          control,
          sampling,
          false,
          { reference.invoke(); if reference.calls >= 200 then control.done = true },
          (),
          (),
          new Control,
          reference.read()
        )
        val actual = raw.samples.entries.map { case (value, count) => value.toDouble -> count }
        assertEquals(s"iteration $iteration: retained observations", expected.values.sortBy(_._1), actual)
    }

  @Test def largeOperationCountsUseReferenceRoundingAndSaturation(): Unit =
    val reference = UpstreamHarness(UpstreamHarness.Fixtures(false, false))
    reference.synchronized {
      for
        count <- Vector(23L, 9007199254740993L, Long.MaxValue / 2, Long.MaxValue - 1);
        batch <- Vector(1, 7); opi <- Vector(1, 13, Int.MaxValue)
      do
        val expected = reference.normalizedOperations(count, batch, opi)
        val raw = RawResult(count, 1, new SampleBuffer)
        val config = Configuration(operationsPerInvocation = opi, measurementBatchSize = batch)
        assertEquals(
          s"count=$count batch=$batch opi=$opi",
          expected,
          Execution.operations(Mode.Throughput, config, Vector(raw), batch, all = false)
        )
    }

  @Test def sampledOperationMetadataFollowsReferenceBatchArithmetic(): Unit =
    val reference = UpstreamHarness(UpstreamHarness.Fixtures(false, false))
    reference.synchronized {
      for
        batch <- Vector(1, 3, 7); opi <- Vector(1, 13, Int.MaxValue);
        physical <- Vector(23L, 9007199254740993L, Long.MaxValue / 2); extra <- Vector(0L, 11L)
      do
        val batches = physical / batch
        val invocations = batches * batch
        val expected = reference.normalizedSampleOperations(batches, extra, batch, opi)
        val raw = RawResult(invocations, 1, new SampleBuffer, allInvocations = invocations + extra)
        val config = Configuration(operationsPerInvocation = opi, measurementBatchSize = batch)
        assertEquals(
          s"sample measured: $physical / $batch * $opi",
          expected._2,
          Execution.operations(Mode.SampleTime, config, Vector(raw), batch, all = false)
        )
        assertEquals(
          s"sample all: $physical / $batch * $opi extra=$extra",
          expected._1,
          Execution.operations(Mode.SampleTime, config, Vector(raw), batch, all = true)
        )
    }

  @Test def sampledAdaptationAgreesWithGeneratedJmh(): Unit =
    for nanos <- Vector(0L, 500000L, 999999L, 1000000L, 2500000L); count <- Vector(2, 3, 21, 22, 23, 200) do
      compare(Mode.SampleTime, UpstreamHarness.Fixtures(false, false), count, 1, 1, nanos, 11, 0, 0)

  @Test def samplingSeedMasksAndLargeDurationsMatchTheReference(): Unit =
    for
      nanos <- Vector(0L, 500000L, 1000000L, Long.MaxValue);
      mask <- Vector(0, 1, 7, Int.MaxValue, -1); seed <- Vector(0L, 42L, Long.MaxValue)
    do
      compare(Mode.SampleTime, UpstreamHarness.Fixtures(false, false), 200, 1, 13, nanos, 11, 0, 0, mask, seed)

  @Test def zeroInvocationTimeFallsBackToEnclosingInterval(): Unit =
    for mode <- Vector(Mode.Throughput, Mode.AverageTime, Mode.SingleShotTime) do
      compare(mode, UpstreamHarness.Fixtures(true, true), 7, 7, 13, 1000000L, 0, 0, 100)

  @Test def fixtureTimeIsExcludedForEveryMode(): Unit =
    for
      mode <- Vector(Mode.Throughput, Mode.AverageTime, Mode.SingleShotTime, Mode.SampleTime);
      fixtures <- Vector(
        UpstreamHarness.Fixtures(false, false),
        UpstreamHarness.Fixtures(true, false),
        UpstreamHarness.Fixtures(false, true),
        UpstreamHarness.Fixtures(true, true)
      )
    do
      compare(mode, fixtures, 7, 7, 3, 1000000L, 11, 1, 100)

end ExecutionParityTest
