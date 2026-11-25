package microbench.runtime

import scala.collection.mutable.ArrayBuffer

import org.junit.Assert.*
import org.junit.Test
import org.openjdk.jmh.annotations.Mode

class ForkEventsTest:

  private val c = Configuration(
    warmupIterations = 1,
    measurementIterations = 2,
    warmupBatchSize = 3,
    measurementBatchSize = 2,
    threads = 2
  )

  private val d = Descriptor(0, "events.execute", Vector(Mode.SingleShotTime), c, Vector.empty, Vector.empty)

  private def observation(warmup: Boolean): IterationResult =
    val workers = Vector.fill(2) {
      val samples = new SampleBuffer
      samples.addCount(7, 2)
      RawResult(if warmup then 3 else 2, 120, samples, Vector(AuxiliaryValue("unicode_λ", "EVENTS", 3)), 10)
    }
    TrialObserver.result(Mode.SingleShotTime, c, warmup, workers)

  private def records(): Vector[String] =
    val out = ArrayBuffer.empty[String]
    val recorder = new ForkEvents.Recorder((index, text) =>
      assertEquals(out.size, index); out += text
    )
    val trial = recorder.trial(d, Mode.SingleShotTime, c, Map.empty)
    for (warmup, index) <- Vector(true -> 1, false -> 1, false -> 2) do
      trial.iteration(warmup, index)
      trial.iterationResult(warmup, index, observation(warmup))
    recorder.flush(); recorder.close()
    out.toVector

  private def replay(records: Vector[String], requireClosed: Boolean = false): Vector[String] =
    val out = ArrayBuffer.empty[String]
    val observer = new RunObserver:
      override def trial(d: Descriptor, mode: Mode, c: Configuration, p: Map[String, String]): TrialObserver =
        new TrialObserver:
          def iteration(warmup: Boolean, index: Int): Unit = out += s"start:$warmup:$index"
          def iterationResult(warmup: Boolean, index: Int, result: IterationResult): Unit =
            val expected = observation(warmup)
            assertEquals(expected.score, result.score, 0)
            assertEquals(expected.workers.map(_.invocations), result.workers.map(_.invocations))
            assertEquals(expected.workers.map(_.auxiliary), result.workers.map(_.auxiliary))
            assertEquals(expected.workers.map(_.samples.entries), result.workers.map(_.samples.entries))
            out += s"result:$warmup:$index"
      override def flush(): Unit = out += "flush"
      override def close(): Unit = out += "close"
    ForkEvents.replay(records, d, Mode.SingleShotTime, c, Map.empty, Vector.empty, observer, requireClosed)
    out.toVector

  @Test def completeRecordsAndEveryCrashPrefixReplayWithoutInventingEvents(): Unit =
    val expected = Vector(
      "start:true:1",
      "result:true:1",
      "start:false:1",
      "result:false:1",
      "start:false:2",
      "result:false:2",
      "flush",
      "close"
    )
    val source = records()
    for size <- 0 to source.size do assertEquals(expected.take(size), replay(source.take(size)))

  @Test def aSuccessfulForkRequiresAClosedEventStream(): Unit =
    val source = records()
    for size <- 0 until source.size do
      assertTrue(scala.util.Try(replay(source.take(size), requireClosed = true)).isFailure)
    assertEquals(8, replay(source, requireClosed = true).size)

  @Test def malformedOrderAndRawObservationsAreRejected(): Unit =
    val source = records()
    val corruptions = Vector(
      source.updated(0, source(0).replace("1\t-1", "2\t-1")),
      source.updated(0, source(0).replace("\t-1\t", "\t0\t")),
      source.updated(0, source(0).replace("true\t1", "true\t2")),
      source.updated(1, source(1).replace("#3,", "#4,")),
      source.updated(1, source(1).replace("#3,120", "#3,-1")),
      source.updated(1, source(1) + "\ttrailing"),
      source.patch(1, Vector(source(0)), 0),
      source.drop(1),
      source :+ source.last,
      source.take(6) ++ Vector(source.last, source(6)),
      source.updated(1, source(1).replaceFirst("^[^#]+#", "1\t-1\tresult\ttrue\t1\tNaN#"))
    )
    corruptions.foreach { records =>
      assertTrue("Accepted invalid records: " + records, scala.util.Try(replay(records)).isFailure)
    }

end ForkEventsTest
