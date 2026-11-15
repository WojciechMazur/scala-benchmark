/*
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  The copyright holders
 * designate this particular file as subject to the "Classpath" exception
 * as provided in the LICENSE file that accompanied this code.
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
 */
package scala.benchmark.runtime

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scala.scalajs.js.typedarray.*

import org.openjdk.jmh.annotations.Mode

/** One JS event loop owns the benchmark; duration checks must run on that loop. */
class ControlFields:
  val notifyControl = new org.openjdk.jmh.infra.Control
  private var stopped = false
  private var polls = 0
  var deadline: Long = 0L
  var failure: Throwable = null
  def announceReady(): Unit = ()
  val started = new Counter
  val finished = new Counter

  def done: Boolean =
    if !stopped && deadline != 0L then
      polls -= 1
      if polls <= 0 then
        polls = 64
        stopped = System.nanoTime() - deadline >= 0
        if stopped then notifyControl.stopMeasurement = true
    stopped

  def done_=(value: Boolean): Unit = stopped = value

final class Counter:
  private var value = 0
  def get(): Int = value

  def incrementAndGet(): Int =
    value += 1; value

/** Host services stay outside linked Scala code, for both JS and Wasm output. */
@js.native @JSGlobal("__scalaBenchmarkHost")
private[runtime] object Host extends js.Object:
  val arguments: js.Array[String] = js.native
  val metadata: js.Dictionary[String] = js.native
  val runtimeVersion: String = js.native
  val gcAvailable: Boolean = js.native
  def requestGC(): Unit = js.native
  def stringify(value: js.Any): String = js.native
  def parse(text: String): js.Dynamic = js.native
  def write(path: String, text: String): Unit = js.native
  def validateOutput(path: String): Unit = js.native
  def consoleWrite(bytes: Int8Array, channel: Int, budget: String): Unit = js.native
  def progress(message: String): Unit = js.native
  def fork(request: String, timeoutNanos: String): String = js.native
  def complete(result: String): Unit = js.native
  def event(sequence: Int, text: String): Unit = js.native
  def takeEvents(): js.Array[String] = js.native
  def userWrite(bytes: Int8Array, channel: Int): Unit = js.native
  def userClose(channel: Int): Unit = js.native
  def readUserOutput(channel: Int): Int8Array = js.native
  def releaseUserOutput(): Unit = js.native
  def exit(status: Int): Unit = js.native

object Platform:
  def restoreCallerInterrupt(): Unit = ()
  def isCallerInterrupted: Boolean = false
  def ensureMeasurementHealthy(): Unit = ()
  private def isWasm: Boolean = scala.scalajs.LinkingInfo.isWebAssembly

  def identity: scala.benchmark.Platform =
    if isWasm then scala.benchmark.Platform.Wasm else scala.benchmark.Platform.JS

  def loadSuite(name: String): Suite = Registry.suite(name).getOrElse(throw new ClassNotFoundException(name))
  def inventory: Vector[String] = Registry.names

  /** What the host knows about itself, together with the build information published to it at startup. */
  def buildMetadata: Vector[(String, String)] = Host.metadata.toVector.sortBy(_._1)
  def description: String = if isWasm then "Scala.js Wasm (Node.js)" else "Scala.js (Node.js)"
  def metadataSection: String = "javascript"
  def runtimeVersion: String = Host.runtimeVersion
  def processorCount: Int = 1
  def requestGC(): Unit = Host.requestGC()

  def validate(config: Configuration): Unit =
    require(config.timeoutNanos > 0, "The JS/Wasm host requires a positive iteration timeout")
    require(config.threads == 1, "The Scala.js backend supports one benchmark thread per process; use -t 1")
    require(
      !config.doGC || Host.gcAvailable,
      "Host collection is unavailable; start Node.js with --expose-gc when using -gc true"
    )

  def validateOutput(path: String): Unit = Host.validateOutput(path)
  def write(path: String, text: String): Unit = Host.write(path, text)

  def consoleStream(destination: java.io.PrintStream, channel: Int, budget: Long): java.io.PrintStream =
    if destination.getClass().getName() == "java.lang.JSConsoleBasedPrintStream" then hostConsoleStream(channel, budget)
    else destination

  private def hostConsoleStream(channel: Int, budget: Long): java.io.PrintStream =
    new java.io.PrintStream(
      new java.io.OutputStream:
        def write(value: Int): Unit = send(Array(value.toByte), 0, 1)
        override def write(bytes: Array[Byte], offset: Int, length: Int): Unit = send(bytes, offset, length)
        private def send(bytes: Array[Byte], offset: Int, length: Int): Unit =
          ForkOutputFailure.check()
          var next = offset
          while next < offset + length do
            val end = math.min(offset + length, next + 8192)
            try
              Host.consoleWrite(bytes.slice(next, end).toTypedArray, channel, budget.toString)
            catch case error: js.JavaScriptException => throw ForkOutputFailure.fail(error.getMessage, error)
            next =
              end
      ,
      true,
      "UTF-8"
    )

  def outputContext[A](options: Options)(body: => A): A =
    val priorOut = System.out
    val priorErr = System.err
    val priorConsole = BenchmarkOutput.console
    val budget = options.configuration(Configuration()).timeoutNanos
    val out = consoleStream(priorOut, 1, budget)
    val err = consoleStream(priorErr, 2, budget)
    val console = BenchmarkOutput.Streams(
      if priorConsole.out eq priorOut then out else priorConsole.out,
      if priorConsole.err eq priorErr then err else priorConsole.err
    )
    System.setOut(out); System.setErr(err)
    try BenchmarkOutput.withConsole(console)(body)
    finally
      if System.out eq out then System.setOut(priorOut)
      if System.err eq err then System.setErr(priorErr)

  def forked(
      suite: String,
      descriptor: Descriptor,
      mode: Mode,
      config: Configuration,
      parameters: Map[String, String],
      warmups: Vector[WarmupRequest],
      failOnError: Boolean,
      observer: RunObserver
  ): Vector[IterationResult] =
    val overrides = js.Dictionary(Configuration.overrides(config).toSeq*)
    val bulk = js.Array(warmups.map { warmup =>
      js.Dynamic.literal(
        suite = warmup.suite,
        index = warmup.index,
        mode = warmup.mode.name(),
        overrides = js.Dictionary(Configuration.overrides(warmup.configuration).toSeq*),
        parameters = js.Dictionary(warmup.parameters.toSeq*)
      )
    }*)
    val request = js.Dynamic.literal(
      failOnError = failOnError,
      events = observer ne RunObserver.Disabled,
      stdio = true,
      directOutput = false,
      suite = suite,
      warmups = bulk,
      index = descriptor.index,
      mode = mode.name(),
      overrides = overrides,
      parameters = js.Dictionary(parameters.toSeq*)
    )
    var returned = false
    var forkFailure: Throwable = null
    val text =
      try
        try
          val result = Host.fork(Host.stringify(request), config.timeoutNanos.toString)
          returned = true
          result
        catch
          case error: js.JavaScriptException
              if js.typeOf(error.exception) == "object" && error.exception != null &&
                error.exception
                  .asInstanceOf[js.Dynamic]
                  .code
                  .asInstanceOf[js.UndefOr[String]]
                  .contains("SCALA_BENCHMARK_OUTPUT") =>
            throw ForkOutputFailure.fail(error.getMessage, error)
          case error: js.JavaScriptException =>
            if js.typeOf(error.exception) == "object" && error.exception != null then
              val captured = error.exception.asInstanceOf[js.Dynamic].benchmarkOutput.asInstanceOf[js.UndefOr[String]]
              captured.foreach { output =>
                if output.nonEmpty then
                  try observer.diagnosticOutput.write(output.getBytes("UTF-8"))
                  catch
                    case failure: Throwable =>
                      failure.addSuppressed(error)
                      throw failure
              }
            throw error
      catch
        case error: Throwable =>
          forkFailure = error
          throw error
      finally
        var cleanupFailure: Throwable = null
        def cleanup(action: => Unit): Unit = try action
        catch
          case error: Throwable =>
            if cleanupFailure == null then cleanupFailure = error
            else if cleanupFailure ne error then cleanupFailure.addSuppressed(error)
        cleanup(drainUserOutput())
        cleanup {
          if observer ne RunObserver.Disabled then
            ForkEvents.replay(
              Host.takeEvents().toVector,
              descriptor,
              mode,
              config,
              parameters,
              warmups,
              observer,
              requireClosed = returned
            )
        }
        if cleanupFailure != null then
          if forkFailure != null && (forkFailure ne cleanupFailure) then cleanupFailure.addSuppressed(forkFailure)
          throw cleanupFailure
    val outcome = Host.parse(text)
    val output = outcome.output.asInstanceOf[String]
    if output.nonEmpty then observer.diagnosticOutput.write(output.getBytes("UTF-8"))
    val decoded = try decode(outcome.result.asInstanceOf[String])
    catch case failure: BenchmarkForkFailure => BenchmarkForkFailure.verify(failure, mode, config)
    ForkResults.verify(mode, config, decoded)

  end forked

  private[runtime] def workerStream(channel: Int): java.io.PrintStream = new java.io.PrintStream(
    new java.io.OutputStream:
      def write(value: Int): Unit = write(Array(value.toByte), 0, 1)
      override def write(bytes: Array[Byte], offset: Int, length: Int): Unit =
        try Host.userWrite(bytes.slice(offset, offset + length).toTypedArray, channel)
        catch case error: js.JavaScriptException => throw new java.io.IOException(error.getMessage, error)
      override def close(): Unit = Host.userClose(channel)
    ,
    true,
    "UTF-8"
  )

  private def drainUserOutput(): Unit =
    try
      for channel <- Vector(1, 2) do
        val destination = if channel == 1 then System.out else System.err
        var bytes = Host.readUserOutput(channel)
        while bytes.length > 0 do
          destination.write(bytes.toArray)
          if destination.checkError() then throw ForkOutputFailure.fail("Benchmark user output destination failed")
          bytes = Host.readUserOutput(channel)
      val input = new java.io.InputStream:
        private var bytes = Array.emptyByteArray
        private var index = 0
        def read(): Int =
          if index == bytes.length then
            bytes = Host.readUserOutput(3).toArray; index = 0
          if bytes.isEmpty then -1
          else
            val value = bytes(index) & 255; index += 1; value
      val reader = new java.io.InputStreamReader(input, "UTF-8")
      try
        val chars = new Array[Char](8192)
        var count = reader.read(chars)
        while count != -1 do
          Console.print(new String(chars, 0, count)); count = reader.read(chars)
      finally reader.close()
    catch case error: js.JavaScriptException => throw ForkOutputFailure.fail(error.getMessage, error)
    finally Host.releaseUserOutput()

  private[runtime] def encodeFailure(error: Throwable): String = Host.stringify(
    js.Dynamic.literal(
      failureVersion = 1,
      benchmarkType = error.getClass.getName,
      message = Option(error.getMessage).getOrElse(""),
      completed = Host.parse(encode(BenchmarkForkFailure.completed(error)))
    )
  )

  private[runtime] def encode(results: Vector[IterationResult]): String =
    Host.stringify(js.Array(results.map { iteration =>
      js.Dynamic.literal(
        score = iteration.score,
        trial = iteration.trial.map { metadata =>
          js.Dynamic.literal(
            start = metadata.startMillis.toString,
            measurementStart = metadata.measurementStartMillis.toString,
            stop = metadata.stopMillis.toString,
            warmupOps = metadata.warmupOperations.toString,
            measurementOps = metadata.measurementOperations.toString
          )
        }.orNull,
        workers = js.Array(iteration.workers.map { worker =>
          js.Dynamic.literal(
            invocations = worker.invocations.toString,
            allInvocations = worker.allInvocations.toString,
            elapsedNanos = worker.elapsedNanos.toString,
            auxiliary = js.Array(worker.auxiliary.map { counter =>
              js.Dynamic.literal(name = counter.name, kind = counter.kind, value = counter.value.toString)
            }*),
            samples = js.Array(worker.samples.entries.map { case (value, count) =>
              js.Array(value.toString, count.toString)
            }*)
          )
        }*)
      )
    }*))

  private[runtime] def decode(text: String): Vector[IterationResult] =
    val parsed = Host.parse(text)
    if !js.Array.isArray(parsed) then
      require(
        parsed != null && parsed.failureVersion.asInstanceOf[Double] == 1 &&
          js.typeOf(parsed.benchmarkType) == "string" && js.typeOf(parsed.message) == "string" && js.Array.isArray(
            parsed.completed
          ),
        "Invalid benchmark failure record"
      )
      throw new BenchmarkForkFailure(
        parsed.benchmarkType.asInstanceOf[String],
        parsed.message.asInstanceOf[String],
        decode(Host.stringify(parsed.completed))
      )
    parsed.asInstanceOf[js.Array[js.Dynamic]].toVector.map { iteration =>
      val workers = iteration.workers.asInstanceOf[js.Array[js.Dynamic]].toVector.map { worker =>
        val samples = new SampleBuffer
        worker.samples
          .asInstanceOf[js.Array[js.Array[String]]]
          .foreach(pair => samples.addCount(pair(0).toLong, pair(1).toLong))
        RawResult(
          worker.invocations.asInstanceOf[String].toLong,
          worker.elapsedNanos.asInstanceOf[String].toLong,
          samples,
          worker.auxiliary.asInstanceOf[js.Array[js.Dynamic]].toVector.map { counter =>
            AuxiliaryValue(
              counter.name.asInstanceOf[String],
              counter.kind.asInstanceOf[String],
              counter.value.asInstanceOf[String].toDouble
            )
          },
          worker.allInvocations.asInstanceOf[String].toLong
        )
      }
      val score = iteration.score.asInstanceOf[Double]
      require(score.isFinite && workers.nonEmpty, "Invalid JS worker result")
      val trial = Option(iteration.trial.asInstanceOf[js.Dynamic]).map { metadata =>
        TrialMetadata(
          metadata.start.asInstanceOf[String].toLong,
          metadata.measurementStart.asInstanceOf[String].toLong,
          metadata.stop.asInstanceOf[String].toLong,
          metadata.warmupOps.asInstanceOf[String].toLong,
          metadata.measurementOps.asInstanceOf[String].toLong
        )
      }
      IterationResult(score, workers, trial)
    }

  end decode

end Platform
