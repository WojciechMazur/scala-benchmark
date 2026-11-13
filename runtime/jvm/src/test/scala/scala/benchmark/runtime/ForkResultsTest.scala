package scala.benchmark.runtime

import org.junit.Assert.*
import org.junit.Test
import org.openjdk.jmh.annotations.Mode

class ForkResultsTest {
  private val trial = Some(TrialMetadata(100, 110, 120, 3, 7))
  private val config = Configuration(measurementIterations = 1, threads = 2, unit = "NANOSECONDS")
  private val workers = Vector(
    RawResult(10, 100, new SampleBuffer, allInvocations = 12),
    RawResult(20, 100, new SampleBuffer, allInvocations = 22)
  )
  private val iteration = IterationResult(0.3, workers, trial)
  private def rejected(
      results: Vector[IterationResult],
      c: Configuration = config,
      mode: Mode = Mode.Throughput
  ): Unit = {
    assertTrue(
      "Invalid transported result was accepted",
      scala.util.Try(ForkResults.verify(mode, c, results)).isFailure
    )
  }
  @Test def incompleteOrInconsistentForksCannotContributeScores(): Unit = {
    assertEquals(Vector(iteration), ForkResults.verify(Mode.Throughput, config, Vector(iteration)))
    rejected(Vector.empty)
    rejected(Vector(iteration.copy(workers = workers.take(1))))
    rejected(Vector(iteration.copy(score = 3.0)))
    rejected(Vector(iteration.copy(score = Double.NaN)))
    rejected(Vector(iteration.copy(workers = workers.updated(0, workers.head.copy(invocations = 0)))))
    rejected(Vector(iteration.copy(workers = workers.updated(0, workers.head.copy(elapsedNanos = -1)))))
    rejected(Vector(iteration.copy(workers = workers.updated(0, workers.head.copy(allInvocations = 1)))))
    rejected(Vector(iteration.copy(trial = None)))
    rejected(
      Vector(iteration, iteration.copy(trial = Some(trial.get.copy(stopMillis = 130)))),
      config.copy(measurementIterations = 2)
    )
  }
  @Test def scoreValidationIsIndependentOfOutputUnitScale(): Unit = {
    val c = config.copy(threads = 1, operationsPerInvocation = 13, unit = "DAYS")
    val raw = RawResult(Long.MaxValue / 2, 1, new SampleBuffer)
    val score = Execution.aggregate(Mode.AverageTime, c, Vector(raw))
    assertTrue(score > 0 && score < 1e-30)
    val valid = Vector(IterationResult(score, Vector(raw), trial))
    assertEquals(valid, ForkResults.verify(Mode.AverageTime, c, valid))
    rejected(Vector(valid.head.copy(score = 0)), c, Mode.AverageTime)
    rejected(Vector(valid.head.copy(score = score * 2)), c, Mode.AverageTime)
  }

  @Test def singleShotAndSamplePopulationsAreValidated(): Unit = {
    rejected(Vector(iteration), mode = Mode.SingleShotTime)
    rejected(Vector(iteration), mode = Mode.SampleTime)
    val samples = new SampleBuffer
    samples.addCount(10, 11)
    rejected(
      Vector(iteration.copy(workers = workers.updated(0, workers.head.copy(samples = samples)))),
      mode = Mode.SampleTime
    )
    val shot = RawResult(7, 0, new SampleBuffer, allInvocations = 7)
    val c = config.copy(threads = 1, measurementBatchSize = 7)
    val valid = Vector(IterationResult(0, Vector(shot), trial))
    assertEquals(valid, ForkResults.verify(Mode.SingleShotTime, c, valid))
  }
  @Test def everyTruncatedBinaryRecordAndTrailingByteIsRejected(): Unit = {
    val path = java.nio.file.Files.createTempFile("benchmark-truncated-fork", ".bin")
    try {
      Main.writeResults(path, Vector(iteration))
      val bytes = java.nio.file.Files.readAllBytes(path)
      for (length <- bytes.indices) {
        java.nio.file.Files.write(path, bytes.take(length))
        assertTrue("Accepted prefix of length " + length, scala.util.Try(Main.readResults(path)).isFailure)
      }
      java.nio.file.Files.write(path, bytes ++ Array[Byte](1))
      assertTrue(scala.util.Try(Main.readResults(path)).isFailure)
    } finally java.nio.file.Files.deleteIfExists(path)
  }

  @Test def explicitBenchmarkFailuresValidateEveryRetainedObservation(): Unit = {
    val c = config.copy(measurementIterations = 2)
    val valid = new BenchmarkForkFailure("test.Failure", "after first iteration", Vector(iteration.copy(trial = None)))
    assertSame(valid, scala.util.Try(BenchmarkForkFailure.verify(valid, Mode.Throughput, c)).failed.get)
    val full = new BenchmarkForkFailure("test.Failure", "invalid completed count", Vector(iteration, iteration))
    assertFalse(
      scala.util
        .Try(BenchmarkForkFailure.verify(full, Mode.Throughput, c))
        .failed
        .get
        .isInstanceOf[BenchmarkForkFailure]
    )
    val corrupt =
      new BenchmarkForkFailure("test.Failure", "corrupt observation", Vector(iteration.copy(score = 3, trial = None)))
    assertFalse(
      scala.util
        .Try(BenchmarkForkFailure.verify(corrupt, Mode.Throughput, c))
        .failed
        .get
        .isInstanceOf[BenchmarkForkFailure]
    )
    val empty = new BenchmarkForkFailure("test.Failure", "before measurement", Vector.empty)
    assertSame(empty, scala.util.Try(BenchmarkForkFailure.verify(empty, Mode.Throughput, c)).failed.get)
  }

  @Test def fullBinaryObservationsRetainCountsAboveTwoToThe53(): Unit = {
    val path = java.nio.file.Files.createTempFile("benchmark-fork-observations", ".bin")
    try {
      val samples = new SampleBuffer
      samples.addCount(2049, 7)
      val count = 9007199254740993L
      val raw = RawResult(
        count,
        1234,
        samples,
        Vector(AuxiliaryValue("events", "EVENTS", 23), AuxiliaryValue("zero", "OPERATIONS", 0)),
        count + 7
      )
      val result = Vector(IterationResult(7.5, Vector(raw), trial))
      Main.writeResults(path, result)
      val decoded = Main.readResults(path)
      assertEquals(result.head.trial, decoded.head.trial)
      assertEquals(count, decoded.head.workers.head.invocations)
      assertEquals(count + 7, decoded.head.workers.head.allInvocations)
      assertEquals(samples.entries, decoded.head.workers.head.samples.entries)
      assertEquals(raw.auxiliary, decoded.head.workers.head.auxiliary)
    } finally java.nio.file.Files.deleteIfExists(path)
  }
}
