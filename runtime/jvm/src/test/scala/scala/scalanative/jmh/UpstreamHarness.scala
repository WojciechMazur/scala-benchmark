package scala.scalanative.jmh

import java.net.URLClassLoader
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.util.concurrent.{CountDownLatch, TimeUnit}
import javax.tools.ToolProvider

import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.generators.core.{BenchmarkGenerator, FileSystemDestination}
import org.openjdk.jmh.generators.reflection.RFGeneratorSource
import org.openjdk.jmh.infra.{BenchmarkParams, Control, IterationParams, ThreadParams}
import org.openjdk.jmh.results.RawResults
import org.openjdk.jmh.runner.{InfraControl, IterationType, WorkloadParams}
import org.openjdk.jmh.runner.options.TimeValue
import scala.jdk.CollectionConverters.*

/** Uses the published JMH generator, not a second implementation of its loops. Only generated clock expressions are
  * substituted, and only in this test build.
  */
private[jmh] object UpstreamHarness {
  val version = "1.37"
  val revision = "2effa2c8310e1d3ad03c8ee02024edca9252b46a"
  final case class Fixtures(setup: Boolean, teardown: Boolean) {
    def name: String = s"setup-$setup-teardown-$teardown"
  }
  final case class Observation(calls: Int, events: Vector[String], elapsed: Long, histogram: Vector[(Long, Long)])
  final case class ResultObservation(
      calls: Int,
      events: Vector[String],
      allOps: Long,
      measuredOps: Long,
      score: Double,
      unit: String,
      values: Vector[(Double, Long)]
  )
  private val runDirectory = Paths
    .get("target/execution-parity/oracle")
    .toAbsolutePath
    .resolve(java.lang.Long.toUnsignedString(System.nanoTime()))
  private val references = scala.collection.mutable.Map.empty[Fixtures, Reference]
  def apply(fixtures: Fixtures): Reference = references.synchronized {
    references.getOrElseUpdate(fixtures, new Reference(fixtures))
  }

  private def digest(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  final class Reference(fixtures: Fixtures) {
    val directory: Path = runDirectory.resolve(fixtures.name)
    private val classes = Files.createDirectories(directory.resolve("classes"))
    private val source = Files.createDirectories(directory.resolve("source"))
    private val generated = Files.createDirectories(directory.resolve("generated"))
    private val resources = Files.createDirectories(directory.resolve("resources"))
    private val dependencies = Vector(
      classOf[Mode],
      classOf[RFGeneratorSource],
      classOf[org.apache.commons.math3.stat.descriptive.StatisticalSummary]
    )
      .map(_.getProtectionDomain.getCodeSource.getLocation.toURI)
      .map(Paths.get(_))
      .distinct
    private val classpath = (dependencies :+ classes).mkString(java.io.File.pathSeparator)
    private def write(path: Path, text: String): Unit = {
      Files.createDirectories(path.getParent); Files.write(path, text.getBytes(UTF_8)); ()
    }
    private def compile(files: Vector[Path]): Unit = {
      val compiler = ToolProvider.getSystemJavaCompiler
      require(compiler != null, "Execution parity tests require a JDK with javac")
      val diagnostics = new java.io.ByteArrayOutputStream
      val arguments = Vector("--release", "8", "-classpath", classpath, "-d", classes.toString) ++ files.map(_.toString)
      val exit = compiler.run(null, diagnostics, diagnostics, arguments*)
      require(exit == 0, s"Upstream fixture compilation failed in $directory:\n${diagnostics.toString("UTF-8")}")
    }
    private val clockSource = """package execution.oracle;
      |import java.util.*;
      |import org.openjdk.jmh.runner.InfraControl;
      |public final class ScriptedClock {
      | public static long now, tick, work, fixtureCost;
      | public static int calls, stopAfter, maximumCalls;
      | public static InfraControl control;
      | public static final List<String> events = Collections.synchronizedList(new ArrayList<String>());
      | public static volatile boolean gate, traceLifecycle;
      | public static String failAt;
      | public static void record(String stage) {
      |  if (traceLifecycle) events.add(stage);
      |  if (stage.equals(failAt)) throw new IllegalStateException(stage);
      | }
      | public static void construct() { record("construct"); }
      | public static void trialSetup() { record("trialSetup"); }
      | public static void trialTeardown() { record("trialTeardown"); }
      | public static void iterationTeardown() { record("iterationTeardown"); }
      | public static java.util.concurrent.CountDownLatch setupEntered, releaseSetup, fastWork;
      | public static void iterationSetup() throws InterruptedException {
      |  record("iterationSetup");
      |  if (gate && Thread.currentThread().getName().equals("oracle-slow")) { setupEntered.countDown(); if (!releaseSetup.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Oracle setup was not released"); }
      | }
      | public static long nanoTime() { long value = now; now += tick; return value; }
      | public static void setup() { if (traceLifecycle) record("invocationSetup"); else events.add("setup"); now += fixtureCost; }
      | public static void teardown() { if (traceLifecycle) record("invocationTeardown"); else events.add("teardown"); now += fixtureCost; }
      | public static synchronized void invoke() { if (gate && Thread.currentThread().getName().equals("oracle-fast")) fastWork.countDown(); if (traceLifecycle) record("invoke"); else if (events.size() < 10000) events.add("invoke"); calls++; if (calls > maximumCalls) throw new AssertionError("Oracle invocation budget exceeded"); now += work; if (calls >= stopAfter && control != null) control.isDone = true; }
      |}
      |""".stripMargin
    private val benchmarkSource = s"""package execution.oracle;
      |import org.openjdk.jmh.annotations.*;
      |@State(Scope.Thread)
      |public class OracleBenchmark {
      | public OracleBenchmark() { ScriptedClock.construct(); }
      | @Setup(Level.Trial) public void trialStart() { ScriptedClock.trialSetup(); }
      | @TearDown(Level.Trial) public void trialEnd() { ScriptedClock.trialTeardown(); }
      | @TearDown(Level.Iteration) public void iterationEnd() { ScriptedClock.iterationTeardown(); }
      | ${if (fixtures.setup) "@Setup(Level.Invocation) public void before() { ScriptedClock.setup(); }" else ""}
      | ${
                                      if (fixtures.teardown)
                                        "@TearDown(Level.Invocation) public void after() { ScriptedClock.teardown(); }"
                                      else ""
                                    }
      | @Setup(Level.Iteration) public void iteration() throws InterruptedException { ScriptedClock.iterationSetup(); }
      | @Benchmark public void execute() { ScriptedClock.invoke(); }
      |}
      |""".stripMargin
    private val clockPath = source.resolve("execution/oracle/ScriptedClock.java")
    private val benchmarkPath = source.resolve("execution/oracle/OracleBenchmark.java")
    write(clockPath, clockSource); write(benchmarkPath, benchmarkSource)
    compile(Vector(clockPath, benchmarkPath))
    private val loader = new URLClassLoader(Array(classes.toUri.toURL), getClass.getClassLoader)
    private val input = new RFGeneratorSource
    input.processClasses(loader.loadClass("execution.oracle.OracleBenchmark"))
    private val destination = new FileSystemDestination(resources.toFile, generated.toFile)
    private val generator = new BenchmarkGenerator
    generator.generate(input, destination); generator.complete(input, destination)
    require(!destination.hasErrors, destination.getErrors.asScala.mkString("\n"))
    private val paths = {
      val stream = Files.walk(generated)
      try stream.iterator.asScala.filter(_.toString.endsWith(".java")).toVector.sorted
      finally stream.close()
    }
    private val originals = paths.map(p => p -> new String(Files.readAllBytes(p), UTF_8))
    originals.foreach { case (path, text) =>
      write(directory.resolve("original").resolve(generated.relativize(path)), text)
      write(path, text.replace("System.nanoTime()", "execution.oracle.ScriptedClock.nanoTime()"))
    }
    private val normalization = originals.iterator
      .map(_._2)
      .flatMap { text =>
        "(?s)res\\.allOps \\*= opsPerInv;\\s*res\\.allOps /= batchSize;\\s*res\\.measuredOps \\*= opsPerInv;\\s*res\\.measuredOps /= batchSize;".r
          .findFirstIn(text)
      }
      .next()
    private val sampleNormalization = originals.iterator
      .map(_._2)
      .flatMap { text =>
        "(?s)res\\.allOps \\+= res\\.measuredOps \\* batchSize;\\s*res\\.allOps \\*= opsPerInv;\\s*res\\.allOps /= batchSize;\\s*res\\.measuredOps \\*= opsPerInv;".r
          .findFirstIn(text)
      }
      .next()
    private val arithmetic = source.resolve("execution/oracle/Normalization.java")
    write(
      arithmetic,
      "package execution.oracle; import org.openjdk.jmh.results.*; public class Normalization { " +
        "public static BenchmarkTaskResult apply(double count, int batchSize, int opsPerInv) { RawResults res = new RawResults(); " +
        "res.allOps = count; res.measuredOps = count; " + normalization +
        "return new BenchmarkTaskResult((long)res.allOps, (long)res.measuredOps); } " +
        "public static double measured(double count, int batchSize, int opsPerInv) { RawResults res = new RawResults(); " +
        "res.allOps = count; res.measuredOps = count; " + normalization + "return res.measuredOps; } " +
        "public static BenchmarkTaskResult sample(long batches, long extra, int batchSize, int opsPerInv) { RawResults res = new RawResults(); " +
        "res.allOps = extra; res.measuredOps = batches; " + sampleNormalization +
        "return new BenchmarkTaskResult((long)res.allOps, (long)res.measuredOps); } }"
    )
    compile(paths :+ arithmetic)
    private val clock = loader.loadClass("execution.oracle.ScriptedClock")
    private val harness = loader.loadClass("execution.oracle.jmh_generated.OracleBenchmark_execute_jmhTest")
    require(
      dependencies.take(2).forall(_.getFileName.toString.endsWith("-1.37.jar")),
      "The execution oracle must use JMH 1.37 artifacts"
    )
    private val artifactLines = dependencies.map { path => s"${path.getFileName}=${digest(Files.readAllBytes(path))}" }
    write(
      directory.resolve("provenance.txt"),
      (Vector(
        s"jmh=$version",
        s"revision=$revision",
        s"fixture.sha256=${digest(benchmarkSource.getBytes(UTF_8))}",
        s"clock.sha256=${digest(clockSource.getBytes(UTF_8))}",
        s"jdk=${System.getProperty("java.runtime.version")}",
        s"os=${System.getProperty("os.name")}/${System.getProperty("os.arch")}"
      ) ++ artifactLines ++
        originals.map { case (p, text) => s"generated.${generated.relativize(p)}=${digest(text.getBytes(UTF_8))}" })
        .mkString("\n") + "\n"
    )

    def set(name: String, value: Any): Unit = clock.getField(name).set(null, value.asInstanceOf[AnyRef])
    def read(): Long = clock.getMethod("nanoTime").invoke(null).asInstanceOf[java.lang.Long].longValue()
    def invoke(): Unit = { clock.getMethod("invoke").invoke(null); () }
    def setup(): Unit = { clock.getMethod("setup").invoke(null); () }
    def teardown(): Unit = { clock.getMethod("teardown").invoke(null); () }
    def calls: Int = clock.getField("calls").getInt(null)
    def events: Vector[String] =
      clock.getField("events").get(null).asInstanceOf[java.util.List[String]].asScala.toVector
    def reset(limit: Int, work: Long, tick: Long, fixtureCost: Long, seed: Long = 42): Unit = {
      set("now", seed); set("tick", tick); set("work", work); set("fixtureCost", fixtureCost)
      set("calls", 0); set("stopAfter", limit);
      set("maximumCalls", if (limit == Int.MaxValue) 5000000 else limit + 1024); set("control", null);
      set("gate", false); set("traceLifecycle", false); set("failAt", "")
      clock.getField("events").get(null).asInstanceOf[java.util.List[String]].clear()
    }
    private def newControl(
        mode: Mode,
        batch: Int,
        opi: Int,
        nanos: Long,
        unit: TimeUnit,
        threads: Int = 1,
        sync: Boolean = false
    ): InfraControl = {
      val iteration = new IterationParams(IterationType.MEASUREMENT, 1, TimeValue.nanoseconds(nanos), batch)
      val params = new BenchmarkParams(
        "execution.oracle.OracleBenchmark.execute",
        "reference",
        sync,
        threads,
        Array(1),
        java.util.Collections.emptyList[String](),
        0,
        0,
        iteration,
        iteration,
        mode,
        new WorkloadParams,
        unit,
        opi,
        "reference",
        java.util.Collections.emptyList[String](),
        "reference",
        "reference",
        "reference",
        version,
        TimeValue.seconds(5)
      )
      val notification = new Control
      val control = new InfraControl(
        params,
        iteration,
        new CountDownLatch(threads),
        new CountDownLatch(threads),
        true,
        true,
        false,
        notification
      )
      set("control", control)
      control
    }
    def run(mode: Mode, batch: Int, opi: Int, nanos: Long, mask: Int): Observation = {
      val control = newControl(mode, batch, opi, nanos, TimeUnit.NANOSECONDS)
      val params = control.benchmarkParams
      val iteration = control.iterationParams
      val notification = control.notifyControl
      val raw = new RawResults
      val samples = new org.openjdk.jmh.util.SampleBuffer
      val suffix = mode.shortLabel()
      val stub = harness.getMethods
        .find(_.getName == s"execute_${suffix}_jmhStub")
        .getOrElse(sys.error("Missing upstream stub: " + suffix))
      val common: Vector[AnyRef] = Vector(
        control,
        raw,
        params,
        iteration,
        new ThreadParams(0, 1, 0, 1, 0, 1, 0, 1, 0, 1),
        null,
        notification,
        Int.box(mask)
      )
      val extra: Vector[AnyRef] =
        if (mode == Mode.SampleTime)
          Vector(
            samples,
            Int.box((control.getDuration(TimeUnit.MILLISECONDS) * 20).toInt),
            Long.box(opi),
            Int.box(batch)
          )
        else if (mode == Mode.SingleShotTime) Vector(Int.box(batch))
        else Vector.empty
      val instances = stub.getParameterTypes
        .drop(common.size + extra.size)
        .map(_.getConstructor().newInstance().asInstanceOf[AnyRef])
        .toVector
      stub.invoke(null, (common ++ extra ++ instances)*)
      val histogram = samples
        .getStatistics(1.0)
        .getRawData
        .asScala
        .map(e => e.getKey.longValue() -> e.getValue.longValue())
        .toVector
        .sortBy(_._1)
      Observation(calls, events, raw.getTime, histogram)
    }
    def runFull(
        mode: Mode,
        batch: Int,
        opi: Int,
        nanos: Long,
        unit: TimeUnit,
        session: AnyRef = null
    ): ResultObservation = {
      val control = newControl(mode, batch, opi, nanos, unit)
      val method = harness.getMethod("execute_" + mode.toString, classOf[InfraControl], classOf[ThreadParams])
      val result = method
        .invoke(if (session == null) newSession() else session, control, new ThreadParams(0, 1, 0, 1, 0, 1, 0, 1, 0, 1))
        .asInstanceOf[org.openjdk.jmh.results.BenchmarkTaskResult]
      val primary = result.getResults.asScala.find(_.getRole.isPrimary).get
      val values =
        primary.getStatistics.getRawData.asScala.map(e => e.getKey.doubleValue() -> e.getValue.longValue()).toVector
      ResultObservation(
        calls,
        events,
        result.getAllOps,
        result.getMeasuredOps,
        primary.getScore,
        primary.getScoreUnit,
        values
      )
    }
    def newSession(): AnyRef = harness.getConstructor().newInstance().asInstanceOf[AnyRef]
    def normalizedOperations(count: Long, batch: Int, opi: Int): Long =
      loader
        .loadClass("execution.oracle.Normalization")
        .getMethod("apply", java.lang.Double.TYPE, java.lang.Integer.TYPE, java.lang.Integer.TYPE)
        .invoke(null, Double.box(count.toDouble), Int.box(batch), Int.box(opi))
        .asInstanceOf[org.openjdk.jmh.results.BenchmarkTaskResult]
        .getMeasuredOps
    def normalizedScoreOperations(count: Long, batch: Int, opi: Int): Double =
      loader
        .loadClass("execution.oracle.Normalization")
        .getMethod("measured", java.lang.Double.TYPE, java.lang.Integer.TYPE, java.lang.Integer.TYPE)
        .invoke(null, Double.box(count.toDouble), Int.box(batch), Int.box(opi))
        .asInstanceOf[java.lang.Double]
        .doubleValue()
    def normalizedSampleOperations(batches: Long, extra: Long, batch: Int, opi: Int): (Long, Long) = {
      val result = loader
        .loadClass("execution.oracle.Normalization")
        .getMethod("sample", java.lang.Long.TYPE, java.lang.Long.TYPE, java.lang.Integer.TYPE, java.lang.Integer.TYPE)
        .invoke(null, Long.box(batches), Long.box(extra), Int.box(batch), Int.box(opi))
        .asInstanceOf[org.openjdk.jmh.results.BenchmarkTaskResult]
      result.getAllOps -> result.getMeasuredOps
    }
    def record(stage: String): Unit = { clock.getMethod("record", classOf[String]).invoke(null, stage); () }
    def prepareFailure(stage: String): Unit = {
      reset(1, 11, 1, 100)
      set("traceLifecycle", true); set("failAt", stage)
    }
    def failureTrace(stage: String): (Vector[String], Option[String]) = {
      prepareFailure(stage)
      val failure = try { runFull(Mode.SingleShotTime, 1, 1, 1000000L, TimeUnit.NANOSECONDS); None }
      catch { case error: java.lang.reflect.InvocationTargetException => Some(error.getCause.getMessage) }
      val result = events -> failure
      write(
        directory.resolve("failures").resolve(if (stage.isEmpty) "success.txt" else stage + ".txt"),
        result.toString + "\n"
      )
      result
    }
    def concurrentCatchup(sync: Boolean): Boolean = synchronized {
      reset(Int.MaxValue, 1, 0, 0)
      val entered = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val fast = new CountDownLatch(1)
      set("setupEntered", entered); set("releaseSetup", release); set("fastWork", fast); set("gate", true)
      val control = newControl(Mode.Throughput, 1, 1, 1000000L, TimeUnit.NANOSECONDS, 2, sync)
      val failures = new java.util.concurrent.ConcurrentLinkedQueue[Throwable]
      val method = harness.getMethod("execute_Throughput", classOf[InfraControl], classOf[ThreadParams])
      val workers = Vector.tabulate(2) { index =>
        val thread = new Thread(
          new Runnable {
            def run(): Unit = try {
              method.invoke(
                harness.getConstructor().newInstance(),
                control,
                new ThreadParams(index, 2, index, 2, 0, 1, 0, 1, 0, 1)
              )
            } catch { case error: Throwable => failures.add(error); () }
          },
          if (index == 0) "oracle-fast" else "oracle-slow"
        )
        thread.setDaemon(true); thread
      }
      workers.foreach(_.start())
      try {
        require(entered.await(5, TimeUnit.SECONDS), "Upstream slow worker did not enter iteration setup")
        val observed = fast.await(500, TimeUnit.MILLISECONDS)
        write(directory.resolve(s"coordination-sync-$sync.txt"), s"fast-work-before-peer-setup-completes=$observed\n")
        observed
      } finally {
        release.countDown(); control.announceDone()
        workers.foreach(_.join(5000))
        require(!workers.exists(_.isAlive), "Upstream coordination probe left a worker running")
        require(failures.isEmpty, failures.asScala.mkString("\n"))
        set("gate", false)
      }
    }
    def resultEvidence(id: String, expected: ResultObservation, actual: ResultObservation): Unit = {
      write(directory.resolve("results").resolve(id + ".txt"), s"upstream=$expected\nportable=$actual\n")
    }
    def evidence(id: String, expected: Observation, actual: Observation): Unit = {
      write(directory.resolve("observations").resolve(id + ".txt"), s"upstream=$expected\nportable=$actual\n")
    }
  }
}
