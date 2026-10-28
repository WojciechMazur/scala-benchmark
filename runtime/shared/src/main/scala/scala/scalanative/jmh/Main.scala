package scala.scalanative.jmh

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

object Main {
  private val Inventory = "/native-jmh/benchmarks.list"
  private val WorkerFlag = "--native-jmh-worker-v1"
  def main(args: Array[String]): Unit = {
    try {
      if (args.headOption.contains(WorkerFlag)) worker(args)
      else run(Options.parse(args))
    } catch {
      case t: Throwable =>
        System.err.println(s"Native JMH failed: ${t.getMessage}")
        t.printStackTrace(System.err)
        System.exit(1)
    }
  }
  def run(options: Options): Vector[RunResult] = {
    if (options.help) { println(Options.help); return Vector.empty }
    val embedded = Option(getClass.getResourceAsStream(Inventory))
      .map { stream =>
        val source = scala.io.Source.fromInputStream(stream, "UTF-8")
        try source.getLines().filter(_.nonEmpty).toVector
        finally source.close()
      }
      .getOrElse(Vector.empty)
    val suites = (embedded ++ options.suites).distinct.sorted
    require(
      suites.nonEmpty,
      "No benchmark inventory. Enable JmhPlugin on a Native project with sbt-scala-benchmark, or supply --suite <generated module>."
    )
    val includes = options.includes.map(_.r)
    val excludes = options.excludes.map(_.r)
    val selected = suites
      .flatMap { name =>
        val suite = Platform.loadSuite(name)
        suite.metadata().toVector.zipWithIndex.map { case (metadata, index) =>
          (name, suite, metadata, Descriptor.parse(index, metadata))
        }
      }
      .filter { case (_, _, _, descriptor) =>
        (includes.isEmpty || includes.exists(
          _.findFirstIn(descriptor.name).nonEmpty
        )) && !excludes.exists(_.findFirstIn(descriptor.name).nonEmpty)
      }
    require(selected.nonEmpty, "No benchmarks match the selection")
    val knownParameters =
      selected.flatMap(_._4.states.flatMap(_.parameters.map(_.name))).toSet
    require(
      options.parameters.keySet.subsetOf(knownParameters),
      "Unknown parameter override"
    )
    if (options.list) {
      selected.foreach { case (_, _, _, descriptor) =>
        println(descriptor.name)
        if (options.listParameters)
          descriptor.states
            .flatMap(_.parameters)
            .distinct
            .foreach(p => println(s"  ${p.name}: ${p.values.mkString(", ")}"))
      }
      return Vector.empty
    }
    val clock = ClockCalibration.measure()
    println(
      s"# Native JMH, JMH API 1.37; ${Platform.description}; clock delta >= ${clock.minimumDeltaNanos} ns, average call ${clock.averageCallNanos} ns"
    )
    println(
      "Benchmark                                          Mode    Cnt          Score          Error Unit Parameters"
    )
    var completed = Vector.empty[RunResult]
    var failures = Vector.empty[Throwable]
    def save(): Unit = options.output.foreach { output =>
      val text =
        if (options.format == "csv") Reports.csv(completed)
        else Reports.json(completed)
      Files.write(Paths.get(output), text.getBytes(StandardCharsets.UTF_8))
    }
    selected.foreach { case (suiteName, suite, metadata, descriptor) =>
      val modes =
        if (options.modes.nonEmpty) options.modes else descriptor.modes
      val expanded =
        if (modes.contains(Mode.All))
          Vector(
            Mode.Throughput,
            Mode.AverageTime,
            Mode.SampleTime,
            Mode.SingleShotTime
          )
        else modes.distinct
      expanded.foreach { mode =>
        val actual = Descriptor.parse(descriptor.index, metadata, Some(mode))
        val config = options.configuration(actual.configuration)
        actual.warnings.foreach(w => System.err.println("# WARNING: " + w))
        Descriptor.combinations(actual, options.parameters).foreach { parameters =>
          try {
            val result = if (config.forks == 0) {
              System.err.println(
                "# WARNING: forks=0 runs without process isolation"
              )
              Vector(
                Execution.trial(suite, actual, mode, config, parameters)
              )
            } else {
              Vector
                .tabulate(config.warmupForks + config.forks) { fork =>
                  val result =
                    forked(suiteName, actual, mode, config, parameters)
                  if (fork < config.warmupForks) None else Some(result)
                }
                .flatten
            }
            val run =
              RunResult(actual, mode, config, parameters, result, clock)
            completed :+= run
            Reports.console(run)
            save()
          } catch {
            case t: Throwable =>
              failures :+= t
              System.err.println(
                s"# FAILED ${actual.name}: ${t.getMessage}"
              )
              if (options.failOnError) { save(); throw t }
          }
        }
      }
    }
    save()
    if (failures.nonEmpty) {
      val failure = new IllegalStateException(
        s"${failures.size} benchmark configurations failed"
      )
      failures.foreach(failure.addSuppressed)
      throw failure
    }
    completed
  }

  private def forked(
      suite: String,
      descriptor: Descriptor,
      mode: Mode,
      config: Configuration,
      parameters: Map[String, String]
  ): Vector[IterationResult] = {
    val directory = Files.createTempDirectory("native-jmh-")
    val request = directory.resolve("request.properties")
    val result = directory.resolve("result.bin")
    val progress = directory.resolve("deadline")
    val p = new Properties
    p.setProperty("suite", suite);
    p.setProperty("benchmark", descriptor.index.toString);
    p.setProperty("mode", mode.name())
    val fields = Map(
      "-wi" -> config.warmupIterations.toString,
      "-i" -> config.measurementIterations.toString,
      "-w" -> s"${config.warmupNanos}ns",
      "-r" -> s"${config.measurementNanos}ns",
      "-wbs" -> config.warmupBatchSize.toString,
      "-bs" -> config.measurementBatchSize.toString,
      "-t" -> config.threads.toString,
      "-to" -> s"${config.timeoutNanos}ns",
      "-tu" -> config.unit
    )
    fields.foreach { case (k, v) => p.setProperty(k, v) }
    parameters.foreach { case (k, v) => p.setProperty("parameter." + k, v) }
    val output = new FileOutputStream(request.toFile)
    try p.store(output, "Native JMH worker protocol v1")
    finally output.close()
    var process: Process = null
    try {
      val command = new java.util.ArrayList[String]
      Vector(
        Platform.executable,
        WorkerFlag,
        request.toString,
        result.toString,
        progress.toString
      ).foreach(command.add)
      process = new ProcessBuilder(command).inheritIO().start()
      var deadline = System
        .currentTimeMillis() + math.max(30000L, config.timeoutNanos / 1000000L)
      while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
        if (Files.exists(progress)) {
          try
            deadline = new String(
              Files.readAllBytes(progress),
              StandardCharsets.UTF_8
            ).trim.toLong
          catch { case _: NumberFormatException => () }
        }
        if (System.currentTimeMillis() > deadline) {
          process.destroyForcibly(); process.waitFor()
          throw new java.util.concurrent.TimeoutException(
            s"Benchmark fork timed out: ${descriptor.name}"
          )
        }
      }
      require(
        process.exitValue() == 0,
        s"Benchmark fork exited with ${process.exitValue()}"
      )
      readResults(result)
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly(); process.waitFor()
      }
      Vector(request, result, progress).foreach(Files.deleteIfExists)
      Files.deleteIfExists(directory)
    }
  }

  private def worker(args: Array[String]): Unit = {
    require(args.length == 4, "Invalid worker request")
    val p = new Properties
    val input = new FileInputStream(args(1))
    try p.load(input)
    finally input.close()
    val suite = Platform.loadSuite(p.getProperty("suite"))
    val index = p.getProperty("benchmark").toInt
    val mode = Mode.valueOf(p.getProperty("mode"))
    val descriptor =
      Descriptor.parse(index, suite.metadata()(index), Some(mode))
    import scala.jdk.CollectionConverters._
    val pairs =
      p.stringPropertyNames().asScala.toVector.map(k => k -> p.getProperty(k))
    val options = Options(overrides = pairs.filter(_._1.startsWith("-")).toMap)
    val parameters = pairs.collect {
      case (key, value) if key.startsWith("parameter.") =>
        key.stripPrefix("parameter.") -> value
    }.toMap
    val results = Execution.trial(
      suite,
      descriptor,
      mode,
      options.configuration(descriptor.configuration),
      parameters,
      (_, budget) => {
        val deadline =
          System.currentTimeMillis() + math.max(1L, budget / 1000000L)
        Files.write(
          Paths.get(args(3)),
          deadline.toString.getBytes(StandardCharsets.UTF_8)
        )
      }
    )
    writeResults(Paths.get(args(2)), results)
  }

  private[jmh] def writeResults(
      path: Path,
      results: Vector[IterationResult]
  ): Unit = {
    val out = new DataOutputStream(
      new BufferedOutputStream(new FileOutputStream(path.toFile))
    )
    try {
      out.writeInt(0x4e4a4d31); out.writeInt(results.size)
      results.foreach { iteration =>
        out.writeDouble(iteration.score); out.writeInt(iteration.workers.size)
        iteration.workers.foreach { worker =>
          out.writeLong(worker.invocations); out.writeLong(worker.elapsedNanos)
          val histogram = worker.samples.entries
          out.writeInt(histogram.size)
          histogram.foreach { case (value, count) =>
            out.writeLong(value); out.writeLong(count)
          }
        }
      }
    } finally out.close()
  }
  private[jmh] def readResults(path: Path): Vector[IterationResult] = {
    val in = new DataInputStream(
      new BufferedInputStream(new FileInputStream(path.toFile))
    )
    def count(limit: Int): Int = {
      val n = in.readInt();
      require(n >= 0 && n <= limit, "Invalid worker result length"); n
    }
    try {
      require(in.readInt() == 0x4e4a4d31, "Incompatible worker protocol")
      val result = Vector.fill(count(1000000)) {
        val score = in.readDouble()
        val workers = Vector.fill(count(65536)) {
          val operations = in.readLong(); val elapsed = in.readLong()
          val samples = new SampleBuffer
          val size = count(54 * 1024)
          var i = 0
          while (i < size) {
            val value = in.readLong(); val n = in.readLong();
            samples.addCount(value, n); i += 1
          }
          RawResult(operations, elapsed, samples)
        }
        IterationResult(score, workers)
      }
      require(in.read() == -1, "Trailing worker protocol data")
      result
    } finally in.close()
  }
}
