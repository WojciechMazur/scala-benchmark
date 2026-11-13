package scala.scalanative.jmh

import org.openjdk.jmh.annotations.Mode
import scala.collection.mutable.ArrayBuffer

/** Shared behavioral checks run on the JVM executor and actual Native, JS and Wasm executors. */
object TrialObserverChecks {
  private class Probe(fault: String, callbackFault: String = "") extends Suite with TrialObserver {
    private val journal = ArrayBuffer.empty[String]
    private var iteration = 0
    private var tornDown = false
    private var lastControl: IterationControl = null
    def events: Vector[String] = journal.synchronized(journal.toVector)
    private def record(event: String): Unit = journal.synchronized { journal += event }
    private def user(event: String): Unit = {
      record(event)
      if (fault == event || (fault == "late" && event == "work:3")) throw new IllegalStateException("user:" + fault)
    }
    def metadata(): Array[String] = Array.empty
    def instantiate(id: Int): AnyRef = { user("construct"); new Object }
    def assign(id: Int, target: AnyRef, parameter: String, value: String): Unit = ()
    def setup(id: Int, level: Int, target: AnyRef, env: Environment): Unit = {
      if (level == 1) iteration += 1
      user(if (level == 0) "setupTrial" else "setup:" + iteration)
    }
    def teardown(id: Int, level: Int, target: AnyRef, env: Environment): Unit = {
      user(if (level == 0) "teardownTrial" else "teardown:" + iteration)
      if (level == 0) tornDown = true
    }
    override def auxiliary(id: Int, env: Environment): Array[Double] = {
      user("capture:" + iteration)
      Array(if (tornDown) 100.0 + iteration else iteration.toDouble)
    }
    def measure(id: Int, env: Environment, control: IterationControl): RawResult = {
      user("work:" + iteration)
      lastControl = control
      val samples = new SampleBuffer
      samples.addCount(20, 2)
      val invocations = if (control.mode == Mode.SingleShotTime) control.batchSize.toLong else 6L
      RawResult(invocations, 120, samples, allInvocations = invocations + 4)
    }
    def iteration(warmup: Boolean, index: Int): Unit = callback(s"start:$warmup:$index")
    def iterationResult(warmup: Boolean, index: Int, result: IterationResult): Unit = {
      callback(s"result:$warmup:$index")
      assert(result.trial.isEmpty, "An iteration event cannot declare completed trial metadata")
      assert(result.workers.size == 1)
      ForkResults.verify(
        lastControl.mode,
        config().copy(measurementIterations = 1, measurementBatchSize = lastControl.batchSize),
        Vector(result),
        requireTrialMetadata = false
      )
      val expected = if (tornDown) 100.0 + iteration else iteration.toDouble
      assert(result.workers.head.auxiliary.map(_.value) == Vector(expected), events.toString)
    }
    private def callback(event: String): Unit = {
      record(event)
      if (event == callbackFault) throw new IllegalArgumentException("callback:" + event)
    }
  }
  private def config(warmup: Int = 1, measurement: Int = 2): Configuration = Configuration(
    warmupIterations = warmup,
    measurementIterations = measurement,
    warmupNanos = 1000000L,
    measurementNanos = 1000000L,
    warmupBatchSize = 3,
    measurementBatchSize = 2,
    operationsPerInvocation = 7,
    forks = 0,
    synchIterations = false,
    timeoutNanos = 5000000000L,
    unit = "NANOSECONDS"
  )
  private def descriptor(c: Configuration, mode: Mode): Descriptor = Descriptor(
    0,
    "observer.execute",
    Vector(mode),
    c,
    Vector(StateDefinition(0, false, Vector.empty, Vector.empty)),
    Vector.empty,
    counters = Vector(CounterDefinition("events", "EVENTS", 0))
  )
  private def run(probe: Probe, c: Configuration, mode: Mode = Mode.SingleShotTime): Vector[IterationResult] =
    Execution.trial(probe, descriptor(c, mode), mode, c, Map.empty, observer = probe)

  def verify(): Int = {
    var cases = 0
    for (
      mode <- Vector(Mode.SingleShotTime, Mode.Throughput, Mode.AverageTime, Mode.SampleTime);
      (warmup, measurement) <- Vector(1 -> 2, 0 -> 2, 2 -> 0)
    ) {
      val c = config(warmup, measurement)
      val probe = new Probe("")
      val result = run(probe, c, mode)
      val trace = probe.events
      val expected = Vector.newBuilder[String]
      for (i <- 1 to warmup + measurement) {
        val w = i <= warmup
        val index = if (w) i else i - warmup
        expected += s"start:$w:$index"
        if (i == 1) { expected += "construct"; expected += "setupTrial" }
        expected += s"setup:$i"; expected += s"work:$i"; expected += s"teardown:$i"
        if (i == warmup + measurement) expected += "teardownTrial"
        expected += s"capture:$i"; expected += s"result:$w:$index"
      }
      assert(trace == expected.result(), s"$mode $warmup/$measurement: $trace")
      assert(result.size == measurement, "Warmup observations leaked into measurement")
      assert(result.forall(_.trial.nonEmpty))
      if (measurement > 0) assert(result.last.workers.head.auxiliary.head.value == 100.0 + warmup + measurement)
      cases += 1
    }
    for (
      fault <- Vector(
        "construct",
        "setupTrial",
        "setup:1",
        "work:1",
        "teardown:1",
        "capture:1",
        "setup:2",
        "work:2",
        "teardown:2",
        "capture:2",
        "late",
        "teardownTrial",
        "capture:3"
      )
    ) {
      val probe = new Probe(fault)
      val failure = scala.util.Try(run(probe, config())).failed.get
      assert(failure.getMessage == "user:" + fault, failure.toString)
      val trace = probe.events
      assert(trace.head == "start:true:1")
      assert(!trace.contains("result:false:2"), trace.toString)
      if (Set("construct", "setupTrial", "setup:1", "work:1", "teardown:1", "capture:1")(fault))
        assert(!trace.contains("result:true:1"), trace.toString)
      else assert(trace.contains("result:true:1"), trace.toString)
      if (Set("late", "teardownTrial", "capture:3")(fault)) assert(trace.contains("result:false:1"))
      assert(!trace.contains("teardownTrial") || fault == "teardownTrial" || fault == "capture:3", trace.toString)
      cases += 1
    }
    for (
      event <- Vector(
        "start:true:1",
        "result:true:1",
        "start:false:1",
        "result:false:1",
        "start:false:2",
        "result:false:2"
      )
    ) {
      val probe = new Probe("", event)
      val failure = scala.util.Try(run(probe, config())).failed.get
      assert(failure.isInstanceOf[TrialObserverFailure], failure.toString)
      assert(failure.getCause.getMessage == "callback:" + event)
      assert(probe.events.last == event, probe.events.toString)
      cases += 1
    }
    // Warmup batches and measurement batches normalize the same raw work differently.
    val raw = Vector(RawResult(6, 120, new SampleBuffer))
    val c = config()
    assert(TrialObserver.result(Mode.Throughput, c, true, raw).score == 14.0 / 120)
    assert(TrialObserver.result(Mode.Throughput, c, false, raw).score == 21.0 / 120)
    cases + 1
  }
}
