package microbench.runtime

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import HarnessCompiler.compile
import org.junit.Assert.*
import org.junit.Test
import org.openjdk.jmh.annotations.Mode

class HarnessDerivationTest:

  /** HotSpot does not JIT-compile methods above 8000 bytecodes (-XX:+DontCompileHugeMethods). With every measured
    * loop of a class in one method, a class of about ten benchmarks crossed that limit and measured its loops in the
    * interpreter: a floor of ~40 ns/op where upstream JMH reports 1-2 ns.
    */
  @Test def measuredLoopsStayBelowTheHugeMethodLimit(): Unit =
    val benchmarks = (1 to 32).map(i => s"  @Benchmark def b$i(): Array[Int] = { java.util.Arrays.fill(ints, $i); ints }")
    compile(
      s"""package bench
         |import microbench.*
         |@State(Scope.Thread) class Example {
         |  @Param(Array("16", "1024")) var length: Int = 0
         |  private var ints: Array[Int] = null
         |  @Setup(Level.Trial) def prepare(): Unit = ints = new Array[Int](length)
         |${benchmarks.mkString("\n")}
         |}
         |""".stripMargin
    ) { (success, output) =>
      assertTrue(success)
      val classes = Files.walk(output.resolve("generated"))
      val sizes =
        try classes.iterator().asScala.filter(_.toString.endsWith(".class")).flatMap(f => MethodSizes(Files.readAllBytes(f))).toVector
        finally classes.close()
      val loops = sizes.filter((name, _) => name.contains("$measured$"))
      assertEquals("One measured method per benchmark", 32, loops.size)
      assertTrue(s"Loops must together exceed the limit: ${loops.map(_._2).sum}", loops.map(_._2).sum > 8000)
      // Metadata initialisation runs once and may stay interpreted; only measuring code must be compilable.
      val (name, largest) = (loops ++ sizes.filter(_._1 == "measure")).maxBy(_._2)
      assertTrue(s"$name has $largest bytecodes", largest < 2000)
    }

  @Test def partialGroupAnnotationsCollideAsInJmh(): Unit =
    val source = """package bench
      |import microbench.*
      |class Example {
      |  @Benchmark @Group("same") @Warmup(iterations = 1) def a(): Int = 1
      |  @Benchmark @Group("same") @Warmup(time = 1) def b(): Int = 2
      |}
      |""".stripMargin
    compile(source)((success, _) => assertFalse("Partial group annotations must collide", success))
    compile(source, derive = Nil) { (success, output) =>
      assertTrue(success)
      HarnessCompiler.withLoader(output) { loader =>
        val input = new org.openjdk.jmh.generators.reflection.RFGeneratorSource
        input.processClasses(loader.loadClass("bench.Example"))
        val destination = new org.openjdk.jmh.generators.core.FileSystemDestination(
          Files.createDirectory(output.resolve("resources")).toFile,
          Files.createDirectory(output.resolve("generated")).toFile
        )
        val generator = new org.openjdk.jmh.generators.core.BenchmarkGenerator
        generator.generate(input, destination); generator.complete(input, destination)
        assertTrue("JMH must independently reject the same definitions", destination.hasErrors)
        assertTrue(destination.getErrors.toString.contains("Colliding annotations"))
      }
    }

  @Test def overloadedMembersWithinOneGroupAreRejected(): Unit =
    compile(
      """package bench
        |import microbench.*
        |@State(Scope.Thread) class Input
        |class Example {
        |  @Benchmark @Group("same") def run(): Int = 1
        |  @Benchmark @Group("same") def run(input: Input): Int = 2
        |}
        |""".stripMargin
    )((success, _) => assertFalse("Duplicate member names must be rejected as in JMH", success))

  @Test def objectScopedStatesCompileAndExecute(): Unit =
    compile(
      """package bench
        |import microbench.*
        |object Definitions {
        |  @State(Scope.Thread) class Input {
        |    var ready = false
        |    @Setup def setup(): Unit = ready = true
        |  }
        |}
        |class Example {
        |  @Benchmark def run(input: Definitions.Input): Int = { assert(input.ready); 7 }
        |}
        |""".stripMargin
    ) { (success, output) =>
      assertTrue("Object-scoped state did not compile", success)
      HarnessCompiler.withLoader(output) { loader =>
        val suite =
          HarnessCompiler.suite(loader, "bench.Example")
        val descriptor = Descriptor.of(0, suite.metadata()(0), Some(Mode.SingleShotTime))
        val config = descriptor.resolve(Configuration(warmupIterations = 0, measurementIterations = 1, forks = 0))
        assertEquals(1, Execution.trial(suite, descriptor, Mode.SingleShotTime, config, Map.empty).size)
      }
    }

  @Test def inventoryExpandsToConstants(): Unit =
    compile(
      """package bench
      |import microbench.*
      |class Example(unused: Int) {
      |  @Benchmark def run(): Int = 7
      |  @Benchmark @Group("pair") def left(): Int = 1
      |  @Benchmark @Group("pair") @Platforms(Array(Platform.JVM), reason = "workers") def right(): Int = 2
      |}
      |""".stripMargin,
      derive = Nil
    ) { (ok, output) =>
      assertTrue(ok)
      val source = output.getParent.resolve("Inventory.scala")
      Files.write(
        source,
        "package generated; object Example { val inventory = microbench.internal.Inventory.of[bench.Example] }"
          .getBytes(StandardCharsets.UTF_8)
      )
      assertTrue(HarnessCompiler.recompile(output, source))
      HarnessCompiler.withLoader(output) { loader =>
        val entries = HarnessCompiler.inventory(loader, "bench.Example")
        assertEquals(Vector("bench.Example.pair", "bench.Example.run"), entries.map(_.name))
        assertEquals(Vector(microbench.Platform.JVM), entries.head.eligibility.allowed)
        assertEquals(Vector("workers"), entries.head.eligibility.reasons)
        assertEquals(microbench.Platform.values.toVector, entries.last.eligibility.allowed)
      }
    }

  @Test def sourceCompatibleHarnessExecutesDirectCallsAndFixtures(): Unit =
    compile(
      """package bench
        |import microbench.*
        |@State(Scope.Thread) abstract class Base {
        |  @Param(Array("3", "7")) var size: Int = 0
        |  var expected: Int = 0
        |  @Setup(Level.Trial) def initialize(): Unit = expected = size
        |}
        |@State(Scope.Thread) class Input extends Base
        |@State(Scope.Thread)
        |@BenchmarkMode(Array(Mode.SingleShotTime))
        |@Measurement(iterations = 3, batchSize = 7)
        |class Example {
        |  var active = false
        |  var count = 0
        |  @Setup(Level.Invocation) def before(): Unit = { assert(!active); active = true }
        |  @TearDown(Level.Invocation) def after(): Unit = { assert(active); active = false }
        |  @Benchmark def run(input: Input, bh: Blackhole): Int = {
        |    assert(active); assert(input.expected == 7); count += 1; bh.consume(input.expected); count
        |  }
        |  @TearDown(Level.Trial) def check(): Unit = assert(count == 21)
        |}
        |""".stripMargin
    ) { (success, output) =>
      assertTrue("Generated benchmark did not compile", success)
      HarnessCompiler.withLoader(output) { loader =>
        val suite = HarnessCompiler.suite(loader, "bench.Example")
        val descriptor = Descriptor.of(0, suite.metadata()(0))
        assertEquals(Vector(Mode.SingleShotTime), descriptor.modes)
        assertEquals(
          Vector("3", "7"),
          descriptor.states.flatMap(_.parameters).head.values
        )
        val c =
          descriptor.configuration.copy(threads = 2, timeoutNanos = 1000000000L)
        val results = Execution.trial(
          suite,
          descriptor,
          Mode.SingleShotTime,
          c,
          Map("size" -> "7")
        )
        assertEquals(3, results.size)
        assertTrue(results.flatMap(_.workers).forall(_.invocations == 7))
      }
    }

  @Test def genericMethodsUseTheirErasedStateBounds(): Unit = compile(
    """package bench
      |import microbench.*
      |@State(Scope.Thread) class Input { var value = 0 }
      |@State(Scope.Thread) @BenchmarkMode(Array(Mode.SingleShotTime))
      |class Example {
      |  var fixtureInput: Input = null
      |  @Setup def initialize[T <: Input](input: T): Unit = { fixtureInput = input; input.value = 42 }
      |  @Benchmark def run[T <: Input](input: T): Int = { assert(input.value == 0 && fixtureInput.value == 42 && (input ne fixtureInput)); input.value }
      |  @Benchmark def constant[T](): Int = 1
      |}
      |""".stripMargin
  ) { (success, output) =>
    assertTrue("Generic methods did not compile", success)
    HarnessCompiler.withLoader(output) { loader =>
      val suite =
        HarnessCompiler.suite(loader, "bench.Example")
      suite.metadata().zipWithIndex.foreach { case (text, index) =>
        val descriptor = Descriptor.of(index, text)
        val config = descriptor.configuration.copy(forks = 0, timeoutNanos = 1000000000L)
        assertEquals(1, Execution.trial(suite, descriptor, Mode.SingleShotTime, config, Map.empty).size)
      }
    }
  }

  @Test def threadStateIdentityFollowsInjectionSites(): Unit =
    val root = java.nio.file.Paths
      .get("sbt-plugin/src/sbt-test-sources/scala/microbench/examples/StateInjectionBenchmark.scala")
    val file = if Files.exists(root) then root else java.nio.file.Paths.get("..").resolve(root)
    compile(
      new String(Files.readAllBytes(file), StandardCharsets.UTF_8),
      Seq("microbench.examples.StateInjectionBenchmark")
    ) { (success, output) =>
      assertTrue(success)
      HarnessCompiler.withLoader(output) { loader =>
        val suite = HarnessCompiler.suite(loader, "microbench.examples.StateInjectionBenchmark")
        val descriptor = Descriptor.of(0, suite.metadata()(0))
        val config = descriptor.resolve(descriptor.configuration.copy(forks = 0, timeoutNanos = 1000000000L))
        assertEquals(1, Execution.trial(suite, descriptor, Mode.SingleShotTime, config, Map.empty).size)
      }
    }

  @Test def invalidParameterDefaultsAreRejected(): Unit =
    for (kind, value) <- List("Boolean" -> "TRUE", "Char" -> "ab", "Byte" -> "128", "Int" -> "word") do
      compile(s"""package bench
        |import microbench.*
        |@State(Scope.Thread) class Example {
        | @Param(Array("$value")) var input: $kind = scala.compiletime.uninitialized
        | @Benchmark def run(): Unit = ()
        |}
        |""".stripMargin)((success, _) => assertFalse(success))

  @Test def productionHarnessInlinesClockAndWorkWithoutFunctionDispatch(): Unit = compile(
    """package bench
      |import microbench.*
      |@State(Scope.Thread) class Retained {
      |  var value = 42L
      |  @Benchmark def execute(): Long = { value = value * 1664525L + 1013904223L; value }
      |}
      |""".stripMargin,
    Seq("bench.Retained")
  ) { (success, output) =>
    assertTrue(success)
    val artifacts = java.nio.file.Paths.get("target/execution-parity/codegen")
    Files.createDirectories(artifacts)
    val files = Files.walk(output)
    try
      import scala.jdk.CollectionConverters.*
      files.iterator().asScala.filter(p => p.toString.endsWith(".class")).foreach { path =>
        val target = artifacts.resolve(output.relativize(path))
        Files.createDirectories(target.getParent)
        Files.copy(path, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      }
    finally files.close()
    val tool = java.nio.file.Paths.get(
      System.getProperty("java.home"),
      "bin",
      if System.getProperty("os.name").startsWith("Windows") then "javap.exe" else "javap"
    )
    val process = new ProcessBuilder(
      tool.toString,
      "-c",
      "-p",
      "-classpath",
      output.toString,
      "generated.Retained$$anon$1",
      // Each measured loop is a local def of `measure`, lifted to a static method of the enclosing object.
      "generated.Retained$"
    ).redirectErrorStream(true).start()
    val bytes = process.getInputStream.readAllBytes()
    assertEquals(0, process.waitFor())
    val text = new String(bytes, StandardCharsets.UTF_8)
    Files.write(artifacts.resolve("harness.javap.txt"), bytes)
    assertTrue("Measured call was not retained", text.contains("bench/Retained.execute:()J"))
    assertTrue("Return consumption was not retained", text.contains("org/openjdk/jmh/infra/Blackhole.consume:(J)V"))
    assertTrue("Direct monotonic clock reads were not retained", text.contains("java/lang/System.nanoTime:()J"))
    assertFalse(
      "Clock test seam escaped inlining",
      text.linesIterator.exists(line => line.contains("invoke") && line.contains("Loops"))
    )
    assertFalse("Function dispatch entered the measured loop", text.contains("scala/Function0.apply"))
    assertFalse("Test clock entered the production harness", text.contains("ScriptedClock"))
  }

  @Test def injectedInfrastructureTracksWorkerLifecycle(): Unit = compile(
    """package bench
      |import microbench.*
      |import org.openjdk.jmh.runner.IterationType
      |@State(Scope.Thread)
      |@BenchmarkMode(Array(Mode.SingleShotTime))
      |@Warmup(iterations = 2, batchSize = 3)
      |@Measurement(iterations = 3, batchSize = 5)
      |@OperationsPerInvocation(7)
      |@Threads(2)
      |class Example {
      |  var phases = 0
      |  var calls = 0
      |  var expected: BenchmarkParams = null
      |  var iteration: IterationParams = null
      |  @Setup(Level.Trial) def beforeTrial(b: BenchmarkParams): Unit = {
      |    expected = b
      |    assert(b.getBenchmark() == "bench.Example.run")
      |    assert(b.getThreads() == 2 && b.getOpsPerInvocation() == 7)
      |    assert(b.getMode() == Mode.SingleShotTime)
      |    assert(!b.shouldSynchIterations())
      |    assert(b.getWarmup().getCount() == 2 && b.getMeasurement().getCount() == 3)
      |  }
      |  @Setup(Level.Iteration) def beforeIteration(i: IterationParams, c: Control): Unit = {
      |    iteration = i
      |    phases += 1
      |    assert(i.getType() == (if (phases <= 2) IterationType.WARMUP else IterationType.MEASUREMENT))
      |    assert(i.getBatchSize() == (if (phases <= 2) 3 else 5))
      |  }
      |  @Benchmark def run(b: BenchmarkParams, i: IterationParams, c: Control, t: ThreadParams): Int = {
      |    assert(b eq expected); assert(i eq iteration)
      |    assert(c.startMeasurement)
      |    assert(t.getThreadCount() == 2)
      |    calls += 1; calls
      |  }
      |  @TearDown(Level.Iteration) def afterIteration(c: Control): Unit = assert(c.startMeasurement)
      |  @TearDown(Level.Trial) def afterTrial(): Unit = assert(phases == 5 && calls == 21)
      |}
      |""".stripMargin
  ) { (success, output) =>
    assertTrue(success)
    HarnessCompiler.withLoader(output) { loader =>
      val suite = HarnessCompiler.suite(loader, "bench.Example")
      val descriptor = Descriptor.of(0, suite.metadata().head)
      val results = Execution.trial(
        suite,
        descriptor,
        Mode.SingleShotTime,
        descriptor.configuration.copy(synchIterations = false),
        Map.empty
      )
      assertEquals(3, results.size)
      assertTrue(results.flatMap(_.workers).forall(_.invocations == 5))
    }
  }

  @Test def classesWithoutConcreteBenchmarksDeriveEmptySuites(): Unit =
    for
      definition <- List(
        "class Example { def run(): Int = 1 }",
        "abstract class Example { @Benchmark def run(): Int = 1 }"
      )
    do
      compile("package bench; import org.openjdk.jmh.annotations._; " + definition) { (success, output) =>
        assertTrue(success)
        HarnessCompiler.withLoader(output) { loader =>
          assertTrue(HarnessCompiler.suite(loader, "bench.Example").metadata().isEmpty)
        }
      }

  private def inventory(output: Path, cls: String)(check: Array[BenchmarkMetadata] => Unit): Unit =
    HarnessCompiler.withLoader(output)(loader => check(HarnessCompiler.suite(loader, "bench." + cls).metadata()))

  @Test def platformRestrictionsPrecedeHarnessValidation(): Unit =
    val source = """package bench
      |import microbench.*
      |@Platforms(Array(Platform.Native), reason = "Requires grouped workers")
      |class Example {
      |  @Benchmark @Group("group") def run(): Int = 1
      |}
      |""".stripMargin
    compile(source) { (success, output) =>
      assertTrue(success)
      inventory(output, "Example") { metadata =>
        val descriptor = Descriptor.of(0, metadata.head)
        assertEquals(Vector(microbench.Platform.Native), descriptor.eligibility.allowed)
        assertEquals(Vector("Requires grouped workers"), descriptor.eligibility.reasons)
        assertTrue(descriptor.states.isEmpty)
      }
    }

  @Test def platformInheritanceIntersectsOverrides(): Unit = compile(
    """package bench
      |import microbench.*
      |@Platforms(Array(Platform.JVM, Platform.Native, Platform.JS), reason = "base")
      |abstract class Base {
      |  @Benchmark @Platforms(Array(Platform.JVM, Platform.Native)) def run(): Int = 1
      |}
      |@Platforms(Array(Platform.Native, Platform.JS))
      |class Example extends Base {
      |  @Platforms(Array(Platform.JVM, Platform.Native, Platform.JS))
      |  override def run(): Int = 2
      |}
      |""".stripMargin
  ) { (success, output) =>
    assertTrue(success)
    inventory(output, "Example") { metadata =>
      assertEquals(Vector(microbench.Platform.Native), Descriptor.of(0, metadata.head).eligibility.allowed)
    }
  }

  @Test def emptyPlatformsAreDiagnosedOnEveryBackend(): Unit =
    for restriction <- List("Array.empty[Platform]", "Array(Platform.JS)") do
      compile(
        s"""package bench
        |import microbench.*
        |@Platforms(Array(Platform.Native)) class Example {
        |  @Benchmark @Platforms($restriction) def run(): Int = 1
        |}
        |""".stripMargin
      )((ok, _) => assertFalse(ok))

  @Test def inheritedMethodCannotHaveEmptyEffectivePlatforms(): Unit =
    compile(
      """package bench
        |import microbench.*
        |abstract class Base {
        |  @Benchmark @Platforms(Array(Platform.Native)) def run(): Int = 1
        |}
        |@Platforms(Array(Platform.JVM)) class Example extends Base
        |""".stripMargin
    )((ok, _) => assertFalse(ok))

  @Test def dynamicPlatformArraysAreRejected(): Unit =
    for expression <- List("Array(Platform.Native, choose())", "Array(Platform.Native) ++ Array(Platform.JS)") do
      compile(s"""package bench
        |import microbench.*
        |object Config { def choose(): Platform = Platform.JS }
        |import Config.*
        |@Platforms($expression) class Example { @Benchmark def run(): Int = 1 }
        |""".stripMargin)((ok, _) => assertFalse(ok))

  @Test def incompatibleBodiesStillTypecheck(): Unit = compile(
    """package bench
      |import microbench.*
      |@Platforms(Array(Platform.JVM)) class Example {
      |  @Benchmark def run(): Int = "wrong"
      |}
      |""".stripMargin
  )((success, _) => assertFalse(success))

  @Test def auxiliaryCountersCaptureIterationAndTrialTeardown(): Unit =
    val relative = "sbt-plugin/src/sbt-test-sources/scala/microbench/examples/CounterBenchmark.scala"
    val source =
      Vector(java.nio.file.Paths.get(relative), java.nio.file.Paths.get("../" + relative)).find(Files.exists(_)).get
    compile(
      new String(Files.readAllBytes(source), StandardCharsets.UTF_8),
      Seq("microbench.examples.CounterBenchmark")
    ) { (success, output) =>
      assertTrue(success)
      HarnessCompiler.withLoader(output) { loader =>
        val suite = HarnessCompiler.suite(loader, "microbench.examples.CounterBenchmark")
        val descriptor = Descriptor.of(0, suite.metadata().head)
        assertEquals(Set("events", "operations"), descriptor.counters.map(_.name).toSet)
        val config = descriptor.configuration.copy(threads = 2)
        val measured = Execution.trial(suite, descriptor, Mode.SingleShotTime, config, Map.empty)
        val result =
          RunResult(descriptor, Mode.SingleShotTime, config, Map.empty, Vector(measured), ClockCalibration(1, 1))
        assertEquals(Vector("events"), result.secondaryResults.map(_._1))
        val events = result.secondaryResults.head._2
        assertEquals(46.0, events.score, 0.0)
        assertEquals(Vector(18.0, 28.0), events.forks.head.map(_.score))
        assertEquals("#", events.unit)
        assertEquals(Vector(46.0, 46.0), events.scoreConfidence)
        for format <- Reports.formats do assertTrue(Reports.render(Vector(result), format).contains("events"))
      }
    }

  @Test def auxiliaryCounterScopesAreValidated(): Unit = compile(
    """package bench
      |import microbench.*
      |@State(Scope.Benchmark) @AuxCounters class Counters { def count(): Long = 1 }
      |class Example { @Benchmark def run(c: Counters): Int = 1 }
      |""".stripMargin
  )((ok, _) => assertFalse(ok))

  @Test def groupedWorkersShareStatesAndRetainMemberMetrics(): Unit =
    val relative = "sbt-plugin/src/sbt-test-sources/scala/microbench/examples/GroupBenchmark.scala"
    val source =
      Vector(java.nio.file.Paths.get(relative), java.nio.file.Paths.get("../" + relative)).find(Files.exists(_)).get
    compile(
      new String(Files.readAllBytes(source), StandardCharsets.UTF_8),
      Seq("microbench.examples.GroupBenchmark")
    ) { (success, output) =>
      assertTrue(success)
      HarnessCompiler.withLoader(output) { loader =>
        val suite = HarnessCompiler.suite(loader, "microbench.examples.GroupBenchmark")
        val descriptor = Descriptor.of(0, suite.metadata().head)
        assertEquals(Vector("read", "write"), descriptor.members.map(_.name))
        val config = descriptor.resolve(descriptor.configuration)
        assertEquals(6, config.threads)
        val measured = Execution.trial(suite, descriptor, Mode.SingleShotTime, config, Map.empty)
        assertEquals(2, measured.size)
        assertTrue(measured.forall(_.workers.size == 6))
        val result =
          RunResult(descriptor, Mode.SingleShotTime, config, Map.empty, Vector(measured), ClockCalibration(1, 1))
        assertEquals(Vector("read", "write"), result.secondaryResults.map(_._1))
        assertEquals(Vector(2, 4), result.secondaryResults.map(_._2.configuration.threads))
        assertTrue(Reports.json(Vector(result)).contains("\"secondaryMetrics\":{\"read\""))
        for format <- Reports.formats do
          assertTrue(Reports.render(Vector(result), format).contains("read"))
      }
    }

  @Test def groupHarnessesCompile(): Unit = compile(
    """package bench
      |import org.openjdk.jmh.annotations._
      |class Example { @Benchmark @Group("group") def run(): Int = 1 }
      |""".stripMargin
  )((success, _) => assertTrue(success))

  @Test def stateCyclesAreDiagnosed(): Unit = compile(
    """package bench
      |import org.openjdk.jmh.annotations._
      |@State(Scope.Thread) class A { @Setup def setup(b: B): Unit = () }
      |@State(Scope.Thread) class B { @Setup def setup(a: A): Unit = () }
      |class Example { @Benchmark def run(a: A): Int = 1 }
      |""".stripMargin
  )((success, _) => assertFalse(success))

  @Test def immutableParametersAreDiagnosed(): Unit = compile(
    """package bench
      |import org.openjdk.jmh.annotations._
      |@State(Scope.Thread) class Example { @Param(Array("1")) val x = 1; @Benchmark def run(): Int = x }
      |""".stripMargin
  )((success, _) => assertFalse(success))

  @Test def privateParametersAreDiagnosed(): Unit = compile(
    """package bench
      |import org.openjdk.jmh.annotations._
      |@State(Scope.Thread) class Example { @Param(Array("1")) private var x = 1; @Benchmark def run(): Int = x }
      |""".stripMargin
  )((success, _) => assertFalse(success))

  // The JVM backend applies Fork JVM options to its forks; other backends record them as ignored settings.
  @Test def jvmForkSettingsAreNotIgnoredOnTheJvm(): Unit = compile(
    """package bench
      |import org.openjdk.jmh.annotations._
      |@Fork(jvmArgs = Array("-Xmx2g"))
      |class Example { @Benchmark def run(): Int = 1 }
      |""".stripMargin
  ) { (success, output) =>
    assertTrue(success)
    HarnessCompiler.withLoader(output) { loader =>
      val suite = HarnessCompiler.suite(loader, "bench.Example")
      val descriptor = Descriptor.of(0, suite.metadata()(0))
      assertTrue(descriptor.warnings.isEmpty)
    }
  }

  @Test def methodConfigurationOverridesInheritedConfiguration(): Unit = compile(
    """package bench
      |import microbench.*
      |import java.util.concurrent.TimeUnit
      |@BenchmarkMode(Array(Mode.SingleShotTime))
      |@Warmup(iterations = 2, batchSize = 7, time = 2, timeUnit = TimeUnit.MILLISECONDS)
      |@Measurement(iterations = 2, batchSize = 3, time = 9, timeUnit = TimeUnit.MILLISECONDS)
      |@Fork(0)
      |abstract class Base
      |@Warmup(iterations = 1, timeUnit = TimeUnit.MICROSECONDS)
      |class Example extends Base {
      |  @Benchmark @Measurement(iterations = 1, timeUnit = TimeUnit.NANOSECONDS) def run(): Int = 1
      |}
      |""".stripMargin
  ) { (success, output) =>
    assertTrue(success)
    inventory(output, "Example") { metadata =>
      val configuration = Descriptor.of(0, metadata.head).configuration
      assertEquals(1, configuration.warmupIterations)
      assertEquals(7, configuration.warmupBatchSize)
      assertEquals(TimeUnit.MILLISECONDS.toNanos(2), configuration.warmupNanos)
      assertEquals(1, configuration.measurementIterations)
      assertEquals(3, configuration.measurementBatchSize)
      assertEquals(TimeUnit.MILLISECONDS.toNanos(9), configuration.measurementNanos)
      assertEquals(0, configuration.forks)
    }
  }

  @Test def statesFromAnEarlierCompilationAreAssigned(): Unit = compile(
    """package bench
      |import org.openjdk.jmh.annotations._
      |@State(Scope.Thread) class Input {
      |  @Param(Array("7")) var size: Int = 0
      |  @Setup def check(): Unit = assert(size == 7)
      |}
      |""".stripMargin,
    derive = Nil
  ) { (success, output) =>
    assertTrue(success)
    val source = output.getParent.resolve("Second.scala")
    Files.write(
      source,
      "package bench; import org.openjdk.jmh.annotations._; class Example { @Benchmark def run(input: Input): Int = 1 }"
        .getBytes(StandardCharsets.UTF_8)
    )
    assertTrue(HarnessCompiler.recompile(output, source) && HarnessCompiler.derive(output, Seq("bench.Example")))
    HarnessCompiler.withLoader(output) { loader =>
      val suite = HarnessCompiler.suite(loader, "bench.Example")
      val descriptor = Descriptor.of(0, suite.metadata()(0), Some(Mode.SingleShotTime))
      Execution.trial(suite, descriptor, Mode.SingleShotTime, descriptor.configuration, Map("size" -> "7"))
    }
  }

end HarnessDerivationTest

/** Bytecode lengths of every method of one class file, read from its `Code` attributes. */
private object MethodSizes:
  def apply(bytes: Array[Byte]): Map[String, Int] =
    val in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes))
    in.skipBytes(8)
    val count = in.readUnsignedShort()
    val utf8 = scala.collection.mutable.Map.empty[Int, String]
    var index = 1
    while index < count do
      in.readUnsignedByte() match
        case 1           => utf8(index) = in.readUTF()
        case 3 | 4       => in.skipBytes(4)
        case 5 | 6       => in.skipBytes(8); index += 1
        case 7 | 8 | 16 | 19 | 20 => in.skipBytes(2)
        case 9 | 10 | 11 | 12 | 17 | 18 => in.skipBytes(4)
        case 15          => in.skipBytes(3)
        case tag         => throw new IllegalArgumentException(s"constant pool tag $tag")
      index += 1
    in.skipBytes(6)
    in.skipBytes(2 * in.readUnsignedShort())
    def skipAttributes(): Unit = (1 to in.readUnsignedShort()).foreach { _ =>
      in.skipBytes(2); in.skipBytes(in.readInt())
    }
    (1 to in.readUnsignedShort()).foreach { _ => in.skipBytes(6); skipAttributes() }
    (1 to in.readUnsignedShort()).flatMap { _ =>
      in.skipBytes(2)
      val name = utf8(in.readUnsignedShort())
      in.skipBytes(2)
      (1 to in.readUnsignedShort()).flatMap { _ =>
        val attribute = utf8(in.readUnsignedShort())
        val length = in.readInt()
        if attribute == "Code" then
          in.skipBytes(4)
          val code = in.readInt()
          in.skipBytes(length - 8)
          Some(name -> code)
        else
          in.skipBytes(length); None
      }
    }.groupMapReduce(_._1)(_._2)(math.max)
