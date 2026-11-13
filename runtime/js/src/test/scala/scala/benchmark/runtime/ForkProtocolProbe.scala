package scala.benchmark.runtime

/** Standalone local probe; the Node test launcher supplies only JSON host services. */
object ForkProtocolProbe:

  def main(args: Array[String]): Unit =
    val count = 9007199254740993L
    val samples = new SampleBuffer
    samples.addCount(2049, 7)
    val trial = Some(TrialMetadata(100, 110, 120, Long.MaxValue - 1, count))
    val raw = RawResult(
      count,
      1234,
      samples,
      Vector(
        AuxiliaryValue("events", "EVENTS", 23),
        AuxiliaryValue("zero", "OPERATIONS", 0),
        AuxiliaryValue("nan", "OPERATIONS", Double.NaN)
      ),
      count + 7
    )
    val result = Vector(IterationResult(7.5, Vector(raw), trial))
    val encoded = Platform.encode(result)
    val decoded = Platform.decode(encoded)
    assert(decoded.head.trial == trial)
    assert(decoded.head.workers.head.invocations == count)
    assert(decoded.head.workers.head.allInvocations == count + 7)
    assert(decoded.head.workers.head.samples.entries == samples.entries)
    assert(decoded.head.workers.head.auxiliary.take(2) == raw.auxiliary.take(2))
    assert(decoded.head.workers.head.auxiliary.last.value.isNaN)
    val config = Configuration(measurementIterations = 1, unit = "NANOSECONDS")
    assert(
      Execution.operations(
        org.openjdk.jmh.annotations.Mode.Throughput,
        config,
        Vector(raw),
        1,
        false
      ) == 9007199254740992L
    )
    assert(scala.util.Try(ForkResults.verify(org.openjdk.jmh.annotations.Mode.Throughput, config, decoded)).isFailure)
    val lease = new ForkDeadline(0, 10)
    assert(lease.receive(ForkProgress.message(count, count), 0))
    assert(!lease.expired(count - 1))
    assert(lease.expired(count))
    println(
      "JS fork protocol: exact long counts, histograms, metadata, auxiliary metrics and deadline budgets verified"
    )

  end main

end ForkProtocolProbe
