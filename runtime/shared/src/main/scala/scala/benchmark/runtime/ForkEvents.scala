package scala.benchmark.runtime

import org.openjdk.jmh.annotations.Mode

/** Versioned, data-only event records. A crashed writer can leave a valid completed prefix. */
private[runtime] object ForkEvents {
  private def hex(text: String): String = text.iterator.map(c => f"${c.toInt}%04x").mkString
  private def unhex(text: String): String = {
    require(text.length % 4 == 0, "Invalid event string")
    text.grouped(4).map(Integer.parseInt(_, 16).toChar).mkString
  }
  private def encode(result: IterationResult): String = result.score.toString + "#" + result.workers
    .map { w =>
      Vector(
        w.invocations.toString,
        w.elapsedNanos.toString,
        w.allInvocations.toString,
        w.samples.entries.map { case (v, n) => s"$v:$n" }.mkString(";"),
        w.auxiliary.map(v => s"${hex(v.name)}:${hex(v.kind)}:${v.value}").mkString(";")
      ).mkString(",")
    }
    .mkString("/")
  private def decode(text: String): IterationResult = {
    val parts = text.split("#", -1)
    require(parts.length == 2, "Invalid event result")
    val workers = parts(1).split("/", -1).toVector
    require(workers.nonEmpty && workers.size <= 65536, "Invalid event workers")
    IterationResult(
      parts(0).toDouble,
      workers.map { value =>
        val fields = value.split(",", -1)
        require(fields.length == 5, "Invalid event worker")
        val samples = new SampleBuffer
        if (fields(3).nonEmpty) {
          val entries = fields(3).split(";", -1)
          require(entries.length <= 54 * 1024, "Invalid event histogram length")
          entries.foreach { entry =>
            val pair = entry.split(":", -1); require(pair.length == 2, "Invalid event histogram")
            samples.addCount(pair(0).toLong, pair(1).toLong)
          }
        }
        val auxiliary =
          if (fields(4).isEmpty) Vector.empty
          else {
            val entries = fields(4).split(";", -1)
            require(entries.length <= 100000, "Invalid event counters")
            entries.toVector.map { entry =>
              val values = entry.split(":", -1); require(values.length == 3, "Invalid event counter")
              AuxiliaryValue(unhex(values(0)), unhex(values(1)), values(2).toDouble)
            }
          }
        RawResult(fields(0).toLong, fields(1).toLong, samples, auxiliary, fields(2).toLong)
      }
    )
  }
  final class Recorder(publish: (Int, String) => Unit) extends RunObserver {
    private var sequence = 0
    private def emit(action: Int, kind: String, warmup: Boolean = false, index: Int = 0, payload: String = ""): Unit = {
      publish(sequence, s"1\t$action\t$kind\t$warmup\t$index\t$payload")
      sequence += 1
    }
    private def action(id: Int): TrialObserver = new TrialObserver {
      def iteration(warmup: Boolean, index: Int): Unit = emit(id, "start", warmup, index)
      def iterationResult(warmup: Boolean, index: Int, result: IterationResult): Unit =
        emit(id, "result", warmup, index, encode(result))
    }
    override def trial(d: Descriptor, mode: Mode, c: Configuration, p: Map[String, String]): TrialObserver = action(-1)
    override def warmup(
        index: Int,
        d: Descriptor,
        mode: Mode,
        c: Configuration,
        p: Map[String, String]
    ): TrialObserver = action(index)
    override def flush(): Unit = emit(-1, "flush")
    override def close(): Unit = emit(-1, "close")
  }
  def replay(
      records: Vector[String],
      d: Descriptor,
      mode: Mode,
      c: Configuration,
      p: Map[String, String],
      warmups: Vector[WarmupRequest],
      observer: RunObserver,
      requireClosed: Boolean = false
  ): Unit = {
    if (observer eq RunObserver.Disabled) { require(records.isEmpty, "Unexpected fork events"); return }
    var active = Int.MinValue
    var expected = 0
    var pending = false
    var finished = false
    var flushed = false
    var target: TrialObserver = null
    var config: Configuration = null
    var actionMode: Mode = null
    records.foreach { record =>
      require(record.length <= 64 * 1024 * 1024, "Oversized fork event")
      val fields = record.split("\t", -1)
      require(fields.length == 6 && fields(0) == "1", "Invalid fork event protocol")
      val action = fields(1).toInt
      val kind = fields(2)
      if (kind == "flush" || kind == "close") {
        require(
          action == -1 && fields(3) == "false" && fields(4) == "0" && fields(5).isEmpty,
          "Invalid fork lifecycle event"
        )
        if (kind == "flush") {
          require(!flushed && !finished, "Duplicate fork flush"); flushed = true; observer.flush()
        } else { require(flushed && !finished, "Unexpected fork close"); finished = true; observer.close() }
      } else {
        require(!flushed && !finished && (kind == "start" || kind == "result"), "Unexpected fork event")
        require(action >= -1 && action < warmups.size, "Unknown fork action")
        if (action != active) {
          require(
            active != -1 && (active == Int.MinValue || action == -1 || action > active),
            "Out-of-order fork action"
          )
          val context =
            if (action == -1) (d, mode, c, p)
            else {
              val request = warmups(action)
              val suite = Platform.loadSuite(request.suite)
              val descriptor = Descriptor.parse(request.index, suite.metadata()(request.index), Some(request.mode))
              (descriptor, request.mode, WarmupRequest.configuration(request.configuration), request.parameters)
            }
          active = action; expected = 0; pending = false
          val descriptor = context._1.configured(context._3)
          config = descriptor.resolve(context._3); actionMode = context._2
          target = observer.trial(descriptor, actionMode, config, context._4)
        }
        val warmup = fields(3) match {
          case "true"  => true
          case "false" => false
          case _       => throw new IllegalArgumentException("Invalid event iteration type")
        }
        val index = fields(4).toInt
        val w = expected < config.warmupIterations
        val i = if (w) expected + 1 else expected - config.warmupIterations + 1
        require(
          expected < config.warmupIterations + config.measurementIterations && warmup == w && index == i,
          "Out-of-order fork iteration"
        )
        if (kind == "start") {
          require(!pending && fields(5).isEmpty, "Duplicate fork iteration start")
          pending = true
          target.iteration(warmup, index)
        } else {
          require(pending, "Fork result without iteration start")
          val result = decode(fields(5))
          val scoring = config.copy(
            measurementIterations = 1,
            measurementBatchSize = if (warmup) config.warmupBatchSize else config.measurementBatchSize
          )
          ForkResults.verify(actionMode, scoring, Vector(result), requireTrialMetadata = false)
          target.iterationResult(warmup, index, result)
          pending = false; expected += 1
        }
      }
    }
    require(!requireClosed || finished, "Incomplete fork event stream")
  }
}
