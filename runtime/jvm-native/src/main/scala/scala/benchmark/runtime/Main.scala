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

import java.io.{
  BufferedInputStream,
  BufferedOutputStream,
  DataInputStream,
  DataOutputStream,
  FileInputStream,
  FileOutputStream
}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.Properties
import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.Mode

object Main:

  /** Entry point of a benchmark project, called by its generated main class. */
  def run(args: Array[String], suites: Seq[(String, Suite)], buildInfo: Seq[(String, String)]): Unit =
    Registry.install(suites, buildInfo)
    main(args)

  def main(arguments: Array[String]): Unit =
    val args = Platform.commandLine(arguments)
    try
      if args.headOption.contains(ForkProgress.WorkerFlag) then worker(args)
      else BenchmarkRunner.run(Options.parse(args))
    catch
      case t: Throwable =>
        if ForkOutputFailure.failed then Platform.haltWorker()
        System.err.println(s"Scala Native benchmark failed: ${t.getMessage}")
        t.printStackTrace(System.err)
        System.exit(1)

  /** Everything a worker process needs to reproduce the trial: the benchmark, its resolved configuration and
    * parameters, and the bulk warmups that precede it.
    */
  private def writeRequest(
      request: Path,
      suite: String,
      descriptor: Descriptor,
      mode: Mode,
      config: Configuration,
      parameters: Map[String, String],
      warmups: Vector[WarmupRequest],
      failOnError: Boolean,
      observer: RunObserver
  ): Unit =
    val properties = new Properties
    def put(prefix: String, configuration: Configuration, parameters: Map[String, String]): Unit =
      Configuration.overrides(configuration).foreach((key, value) => properties.setProperty(prefix + key, value))
      parameters.foreach((key, value) => properties.setProperty(prefix + "parameter." + key, value))
    properties.setProperty("stdio", "true")
    properties.setProperty("events", (observer ne RunObserver.Disabled).toString)
    properties.setProperty("supervised", "true")
    properties.setProperty("failOnError", failOnError.toString)
    properties.setProperty("suite", suite)
    properties.setProperty("benchmark", descriptor.index.toString)
    properties.setProperty("mode", mode.name())
    put("", config, parameters)
    properties.setProperty("bulk.count", warmups.size.toString)
    warmups.zipWithIndex.foreach { (warmup, index) =>
      val prefix = s"bulk.$index."
      properties.setProperty(prefix + "suite", warmup.suite)
      properties.setProperty(prefix + "benchmark", warmup.index.toString)
      properties.setProperty(prefix + "mode", warmup.mode.name())
      put(prefix, warmup.configuration, warmup.parameters)
    }
    val output = new FileOutputStream(request.toFile)
    try properties.store(output, "Scala Benchmark worker request")
    finally output.close()

  end writeRequest

  private[runtime] def forked(
      suite: String,
      descriptor: Descriptor,
      mode: Mode,
      config: Configuration,
      parameters: Map[String, String],
      warmups: Vector[WarmupRequest],
      failOnError: Boolean,
      observer: RunObserver
  ): Vector[IterationResult] =
    val files = ForkDirectory(Files.createTempDirectory(Platform.temporaryDirectory, "scala-benchmark-"))
    import files.*
    Files.write(userOut, Array.emptyByteArray)
    Files.write(userErr, Array.emptyByteArray)
    Files.write(framework, Array.emptyByteArray)
    writeRequest(request, suite, descriptor, mode, config, parameters, warmups, failOnError, observer)
    var process: Process = null
    var outputRelay: ForkOutput = null
    var userRelays = Vector.empty[ForkOutput]
    var userDestinations = Vector.empty[java.io.PrintStream]
    var frameworkRelay: ForkOutput = null
    def awaitOutput(budget: Long): Unit =
      var primary: Throwable = null
      (Option(outputRelay).toVector ++ userRelays ++ Option(frameworkRelay).toVector).foreach { relay =>
        try relay.await(budget)
        catch
          case failure: Throwable =>
            if primary == null then primary = failure else primary.addSuppressed(failure)
      }
      userDestinations.foreach { stream =>
        try stream.close()
        catch
          case failure: Throwable =>
            if primary == null then primary = failure else primary.addSuppressed(failure)
      }
      if primary != null then throw primary
    var token = 0L
    var attached = false
    var released = false
    var replayed = false
    var cancelled = false
    def replay(): Unit = if !replayed && (observer ne RunObserver.Disabled) then
      replayed = true
      val records = events().zipWithIndex.map { case (path, i) =>
        require(
          path == event(i) && Files.isRegularFile(path),
          "Invalid fork event sequence"
        )
        require(Files.size(path) <= 64 * 1024 * 1024, "Oversized fork event")
        new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
      }
      ForkEvents.replay(
        records,
        descriptor,
        mode,
        config,
        parameters,
        warmups,
        observer,
        requireClosed = !process.isAlive && process.exitValue() == 0
      )
    def releaseTree(): Unit = if attached && !released then
      // The worker publishes its process id before loading benchmark code.
      Platform.releaseFork(
        process,
        token,
        Files.exists(workerReady) &&
          new String(Files.readAllBytes(workerReady), StandardCharsets.UTF_8).trim.toLongOption.contains(process.pid())
      )
      released = true
    try
      val command = new java.util.ArrayList[String]
      (Platform.workerCommand(suite, descriptor) ++ Vector(
        ForkProgress.WorkerFlag,
        request.toString,
        result.toString,
        progress.toString
      )).foreach(command.add)
      process = new ProcessBuilder(command).redirectErrorStream(true).start()
      ForkProcess.managed(process) {
        token = Platform.attachFork(process)
        attached = true
        Files.write(parentReady, Array[Byte](1))
        outputRelay = new ForkOutput(process.getInputStream, observer.diagnosticOutput, raw = true)
        userDestinations = Vector(
          Platform.consoleStream(System.out, 1, config.timeoutNanos),
          Platform.consoleStream(System.err, 2, config.timeoutNanos)
        )
        userRelays = Vector(userOut, userErr).zip(userDestinations).map { case (path, destination) =>
          new ForkOutput(new FileInputStream(path.toFile), destination, raw = true, keepReading = () => process.isAlive)
        }
        val deadline = new ForkDeadline(System.nanoTime(), math.max(ForkProgress.startupNanos, config.timeoutNanos))
        try
          while !process.waitFor(50, TimeUnit.MILLISECONDS) do
            if Files.exists(progress) then
              val text = new String(Files.readAllBytes(progress), StandardCharsets.UTF_8)
              deadline.receive(text, System.nanoTime())
            if deadline.expired(System.nanoTime()) then
              throw new java.util.concurrent.TimeoutException(
                s"Benchmark fork timed out: ${descriptor.name}"
              )
        catch
          case error: InterruptedException =>
            cancelled = true
            throw error
        releaseTree()
        frameworkRelay = new ForkOutput(new FileInputStream(framework.toFile), Console.out)
        replay()
        require(
          process.exitValue() == 0,
          s"Benchmark fork exited with ${process.exitValue()}"
        )
        require(Files.isRegularFile(result), "Missing or non-regular benchmark result file")
        val observations = readResults(result)
        val failure = if Files.exists(failurePath) then
          require(Files.isRegularFile(failurePath), "Invalid benchmark failure record")
          val properties = new Properties
          val input = new FileInputStream(failurePath.toFile)
          try properties.load(input)
          finally input.close()
          require(
            properties.getProperty("version") == "1" && properties.getProperty("type") != null &&
              properties
                .getProperty("message") != null,
            "Invalid benchmark failure record"
          )
          Some(
            new BenchmarkForkFailure(properties.getProperty("type"), properties.getProperty("message"), observations)
          )
        else None
        val verified = if failure.nonEmpty then observations else ForkResults.verify(mode, config, observations)
        failure.foreach(BenchmarkForkFailure.verify(_, mode, config))
        awaitOutput(config.timeoutNanos)
        verified
      } {
        releaseTree()
        if frameworkRelay == null && Files.isRegularFile(framework) then
          frameworkRelay = new ForkOutput(new FileInputStream(framework.toFile), Console.out)
        var replayFailure: Throwable = null
        try if !cancelled && !Platform.isCallerInterrupted then replay()
        catch
          case error: Throwable =>
            replayFailure = error
            throw error
        finally
          try awaitOutput(1000000000L)
          catch
            case error: Throwable =>
              if replayFailure != null && !error.isInstanceOf[UnfinishedWorkersException] then
                replayFailure.addSuppressed(error)
              else
                if replayFailure != null then error.addSuppressed(replayFailure)
                throw error
      }
    finally files.delete()
    end try

  end forked

  private def worker(args: Array[String]): Unit =
    require(args.length == 4, "Invalid worker request")
    Platform.prepareWorker()
    val p = new Properties
    ForkLifeline.start(
      System.in,
      () =>
        if p.getProperty("supervised", "false").toBoolean then
          scala.util.Try(ForkDirectory(Paths.get(args(1)).getParent).delete())
        Platform.haltWorker()
    )
    val input = new FileInputStream(args(1))
    try p.load(input)
    finally input.close()
    val files = ForkDirectory(Paths.get(args(1)).getParent)
    Files.write(files.workerReady, Platform.processId.toString.getBytes(StandardCharsets.UTF_8))
    if p.getProperty("supervised", "false").toBoolean then
      val started = System.nanoTime()
      while !Files.exists(files.parentReady) do
        require(
          System.nanoTime() - started < ForkProgress.startupNanos,
          "Coordinator did not establish process containment"
        )
        Thread.sleep(1)
    if p.getProperty("stdio", "false").toBoolean then
      def append(path: Path) = new java.io.PrintStream(new FileOutputStream(path.toFile, true), true, "UTF-8")
      val framework = append(files.framework)
      try
        BenchmarkOutput.worker(
          append(files.userOut),
          append(files.userErr),
          BenchmarkOutput.Streams(framework, framework)
        )(
          executeWorker(args, p, files)
        )
      finally framework.close()
    else executeWorker(args, p, files)

  end worker

  /** Reads back what `writeRequest` stored under `prefix`. */
  private def readEntry(p: Properties, prefix: String): (Suite, WarmupRequest, Descriptor) =
    import scala.jdk.CollectionConverters.*
    val entries = p.stringPropertyNames().asScala.collect {
      case key if key.startsWith(prefix) => key.stripPrefix(prefix) -> p.getProperty(key)
    }.toMap
    val suite = BenchmarkOutput.user(Platform.loadSuite(entries("suite")))
    val index = entries("benchmark").toInt
    val mode = Mode.valueOf(entries("mode"))
    val descriptor = Descriptor.of(index, suite.metadata()(index), Some(mode))
    val config = Options(overrides = entries.filter(_._1.startsWith("-"))).configuration(descriptor.configuration)
    val parameters =
      entries.collect { case (key, value) if key.startsWith("parameter.") => key.stripPrefix("parameter.") -> value }
    (suite, WarmupRequest(entries("suite"), index, mode, config, parameters), descriptor)

  private def executeWorker(args: Array[String], p: Properties, files: ForkDirectory): Unit =
    val (suite, trial, descriptor) = readEntry(p, "")
    val mode = trial.mode
    val parameters = trial.parameters
    var sequence = 0L
    val progress: (Int, Long) => Unit = (_, budget) =>
      Files.write(
        Paths.get(args(3)),
        ForkProgress.message(sequence, math.max(1L, budget)).getBytes(StandardCharsets.UTF_8)
      )
      sequence += 1
    val warmups = Vector.tabulate(p.getProperty("bulk.count", "0").toInt)(i => readEntry(p, s"bulk.$i.")._2)
    val observer: RunObserver =
      if !p.getProperty("events", "false").toBoolean then RunObserver.Disabled
      else
        new ForkEvents.Recorder((sequence, text) =>
          Files.write(files.pendingEvent, text.getBytes(StandardCharsets.UTF_8))
          Files.move(files.pendingEvent, files.event(sequence))
          ()
        )
    try
      WarmupRequest.execute(warmups, progress, p.getProperty("failOnError", "true").toBoolean, observer)
      val config = trial.configuration
      progress(-1, ForkProgress.budget(math.max(config.warmupNanos, config.measurementNanos), config.timeoutNanos))
      val results = try
        Execution.trial(
          suite,
          descriptor,
          mode,
          config,
          parameters,
          progress,
          retainCompletedOnFailure = true,
          observer = observer.trial(descriptor, mode, config, parameters)
        )
      catch
        case error: TrialObserverFailure => throw error
        case error: Throwable            =>
          error.printStackTrace(Console.err)
          writeResults(Paths.get(args(2)), BenchmarkForkFailure.completed(error))
          val failure = new Properties
          failure.setProperty("version", "1")
          failure.setProperty("type", error.getClass.getName)
          failure.setProperty("message", Option(error.getMessage).getOrElse(""))
          val output = new FileOutputStream(Paths.get(args(2)).resolveSibling("failure.properties").toFile)
          try failure.store(output, "Benchmark failure")
          finally output.close()
          return
      writeResults(Paths.get(args(2)), results)
    finally
      observer.flush(); observer.close()
    end try

  end executeWorker

  private[benchmark] def writeResults(
      path: Path,
      results: Vector[IterationResult]
  ): Unit =
    val out = new DataOutputStream(
      new BufferedOutputStream(new FileOutputStream(path.toFile))
    )
    try
      out.writeInt(0x4e4a4d34); out.writeInt(results.size)
      results.foreach { iteration =>
        out.writeDouble(iteration.score)
        out.writeBoolean(iteration.trial.nonEmpty)
        iteration.trial.foreach { metadata =>
          out.writeLong(metadata.startMillis); out.writeLong(metadata.measurementStartMillis);
          out.writeLong(metadata.stopMillis)
          out.writeLong(metadata.warmupOperations); out.writeLong(metadata.measurementOperations)
        }
        out.writeInt(iteration.workers.size)
        iteration.workers.foreach { worker =>
          out.writeLong(worker.invocations); out.writeLong(worker.elapsedNanos)
          val histogram = worker.samples.entries
          out.writeInt(histogram.size)
          histogram.foreach { case (value, count) =>
            out.writeLong(value); out.writeLong(count)
          }
          out.writeLong(worker.allInvocations)
          out.writeInt(worker.auxiliary.size)
          worker.auxiliary.foreach { counter =>
            out.writeUTF(counter.name); out.writeUTF(counter.kind); out.writeDouble(counter.value)
          }
        }
      }
    finally out.close()

  end writeResults

  private[benchmark] def readResults(path: Path): Vector[IterationResult] =
    val in = new DataInputStream(
      new BufferedInputStream(new FileInputStream(path.toFile))
    )
    def count(limit: Int): Int =
      val n = in.readInt();
      require(n >= 0 && n <= limit, "Invalid worker result length"); n
    try
      require(in.readInt() == 0x4e4a4d34, "Incompatible worker protocol")
      val result = Vector.fill(count(1000000)) {
        val score = in.readDouble()
        val trial =
          if in.readBoolean() then
            Some(TrialMetadata(in.readLong(), in.readLong(), in.readLong(), in.readLong(), in.readLong()))
          else None
        val workers = Vector.fill(count(65536)) {
          val operations = in.readLong(); val elapsed = in.readLong()
          val samples = new SampleBuffer
          val size = count(54 * 1024)
          var i = 0
          while i < size do
            val value = in.readLong(); val n = in.readLong();
            samples.addCount(value, n); i += 1
          val allInvocations = in.readLong()
          val auxiliary = Vector.fill(count(100000)) {
            AuxiliaryValue(in.readUTF(), in.readUTF(), in.readDouble())
          }
          RawResult(operations, elapsed, samples, auxiliary, allInvocations)
        }
        IterationResult(score, workers, trial)
      }
      require(in.read() == -1, "Trailing worker protocol data")
      result
    finally in.close()

  end readResults

end Main
