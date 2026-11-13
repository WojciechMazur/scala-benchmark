package scala.scalanative.jmh.plugin

import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import org.junit.Assert._
import org.junit.Test

import org.openjdk.jmh.annotations.Mode

import scala.scalanative.jmh._

class CompilerTest {
  private val attributes =
    """package scala.scalanative.annotation
      |class alwaysinline extends scala.annotation.StaticAnnotation
      |class nospecialize extends scala.annotation.StaticAnnotation
      |""".stripMargin
  private def compile(source: String, options: List[String] = Nil)(check: (Boolean, Path) => Unit): Unit = {
    val directory = Files.createTempDirectory("native-jmh-compiler-")
    val output = Files.createDirectory(directory.resolve("classes"))
    val input = directory.resolve("Benchmark.scala")
    val annotations = directory.resolve("Attributes.scala")
    Files.write(input, source.getBytes(StandardCharsets.UTF_8))
    Files.write(annotations, attributes.getBytes(StandardCharsets.UTF_8))
    try {
      val reporter = dotty.tools.dotc.Main.process(
        Array(
          "-classpath",
          System.getProperty("scalanative.jmh.testClasspath"),
          "-d",
          output.toString,
          "-Xplugin:" + System.getProperty("scalanative.jmh.plugin"),
          annotations.toString,
          input.toString
        ) ++ options.map("-P:scalanative-jmh:" + _)
      )
      check(!reporter.hasErrors, output)
    } finally {
      val files = Files.walk(directory)
      try {
        import scala.jdk.CollectionConverters._
        files.iterator().asScala.toVector.reverse.foreach(Files.delete)
      } finally files.close()
    }
  }

  @Test def partialGroupAnnotationsCollideAsInJmh(): Unit = {
    val source = """package bench
      |import scala.benchmark.*
      |class Example {
      |  @Benchmark @Group("same") @Warmup(iterations = 1) def a(): Int = 1
      |  @Benchmark @Group("same") @Warmup(time = 1) def b(): Int = 2
      |}
      |""".stripMargin
    compile(source) { (success, _) => assertFalse("Partial group annotations must collide", success) }
    compile(source, List("backend=jvm")) { (success, output) =>
      assertTrue(success)
      val loader = new URLClassLoader(Array(output.toUri.toURL), getClass.getClassLoader)
      try {
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
      } finally loader.close()
    }
  }

  @Test def overloadedMembersWithinOneGroupAreRejected(): Unit =
    compile(
      """package bench
        |import scala.benchmark.*
        |@State(Scope.Thread) class Input
        |class Example {
        |  @Benchmark @Group("same") def run(): Int = 1
        |  @Benchmark @Group("same") def run(input: Input): Int = 2
        |}
        |""".stripMargin
    ) { (success, _) => assertFalse("Duplicate member names must be rejected as in JMH", success) }

  @Test def objectScopedStatesCompileAndExecute(): Unit =
    compile(
      """package bench
        |import scala.benchmark.*
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
      val loader = new URLClassLoader(Array(output.toUri.toURL), getClass.getClassLoader)
      try {
        val suite =
          loader.loadClass("bench.Example$nativejmh$bootstrapper$").getField("MODULE$").get(null).asInstanceOf[Suite]
        val descriptor = Descriptor.parse(0, suite.metadata()(0), Some(Mode.SingleShotTime))
        val config = descriptor.resolve(Configuration(warmupIterations = 0, measurementIterations = 1, forks = 0))
        assertEquals(1, Execution.trial(suite, descriptor, Mode.SingleShotTime, config, Map.empty).size)
      } finally loader.close()
    }

  @Test def jvmBackendDoesNotGenerateRuntimeDependencies(): Unit =
    compile(
      """package bench
      |import scala.benchmark.*
      |class Example { @Benchmark def run(): Int = 7 }
      |""".stripMargin,
      List("backend=jvm")
    ) { (ok, directory) =>
      assertTrue(ok)
      assertTrue(Files.exists(directory.resolve("bench/Example.class")))
      assertTrue(Files.exists(directory.resolve("bench/Example$nativejmh$bootstrapper$.class")))
    }

  @Test def sourceCompatibleHarnessExecutesDirectCallsAndFixtures(): Unit =
    compile(
      """package bench
        |import scala.benchmark.*
        |@State(Scope.Thread) abstract class Base {
        |  @Param(Array("3", "7")) private var size: Int = 0
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
      val loader =
        new URLClassLoader(Array(output.toUri.toURL), getClass.getClassLoader)
      try {
        val suite = loader
          .loadClass("bench.Example$nativejmh$bootstrapper$")
          .getField("MODULE$")
          .get(null)
          .asInstanceOf[Suite]
        val descriptor = Descriptor.parse(0, suite.metadata()(0))
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
      } finally loader.close()
    }

  @Test def genericMethodsUseTheirErasedStateBounds(): Unit = compile(
    """package bench
      |import scala.benchmark.*
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
    val loader = new URLClassLoader(Array(output.toUri.toURL), getClass.getClassLoader)
    try {
      val suite =
        loader.loadClass("bench.Example$nativejmh$bootstrapper$").getField("MODULE$").get(null).asInstanceOf[Suite]
      suite.metadata().zipWithIndex.foreach { case (text, index) =>
        val descriptor = Descriptor.parse(index, text)
        val config = descriptor.configuration.copy(forks = 0, timeoutNanos = 1000000000L)
        assertEquals(1, Execution.trial(suite, descriptor, Mode.SingleShotTime, config, Map.empty).size)
      }
    } finally loader.close()
  }

  @Test def threadStateIdentityFollowsInjectionSites(): Unit = {
    val root = java.nio.file.Paths
      .get("sbt-plugin/src/sbt-test-sources/scala/scala/scalanative/jmh/examples/StateInjectionBenchmark.scala")
    val file = if (Files.exists(root)) root else java.nio.file.Paths.get("..").resolve(root)
    compile(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)) { (success, output) =>
      assertTrue(success)
      val loader = new URLClassLoader(Array(output.toUri.toURL), getClass.getClassLoader)
      try {
        val suite = loader
          .loadClass("scala.scalanative.jmh.examples.StateInjectionBenchmark$nativejmh$bootstrapper$")
          .getField("MODULE$")
          .get(null)
          .asInstanceOf[Suite]
        val descriptor = Descriptor.parse(0, suite.metadata()(0))
        val config = descriptor.resolve(descriptor.configuration.copy(forks = 0, timeoutNanos = 1000000000L))
        assertEquals(1, Execution.trial(suite, descriptor, Mode.SingleShotTime, config, Map.empty).size)
      } finally loader.close()
    }
  }

  @Test def invalidParameterDefaultsAreRejected(): Unit = {
    for ((kind, value) <- List("Boolean" -> "TRUE", "Char" -> "ab", "Byte" -> "128", "Int" -> "word")) {
      compile(s"""package bench
        |import scala.benchmark.*
        |@State(Scope.Thread) class Example {
        | @Param(Array("$value")) var input: $kind = scala.compiletime.uninitialized
        | @Benchmark def run(): Unit = ()
        |}
        |""".stripMargin)((success, _) => assertFalse(success))
    }
  }

  @Test def productionHarnessInlinesClockAndWorkWithoutFunctionDispatch(): Unit = compile(
    """package bench
      |import scala.benchmark.*
      |@State(Scope.Thread) class Retained {
      |  var value = 42L
      |  @Benchmark def execute(): Long = { value = value * 1664525L + 1013904223L; value }
      |}
      |""".stripMargin
  ) { (success, output) =>
    assertTrue(success)
    val label = java.nio.file.Paths.get(System.getProperty("scalanative.jmh.plugin")).getFileName.toString
    val artifacts = java.nio.file.Paths.get("target/execution-parity/codegen").resolve(label)
    Files.createDirectories(artifacts)
    val files = Files.walk(output)
    try {
      import scala.jdk.CollectionConverters.*
      files.iterator().asScala.filter(p => p.toString.endsWith(".class")).foreach { path =>
        val target = artifacts.resolve(output.relativize(path))
        Files.createDirectories(target.getParent)
        Files.copy(path, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      }
    } finally files.close()
    val tool = java.nio.file.Paths.get(
      System.getProperty("java.home"),
      "bin",
      if (System.getProperty("os.name").startsWith("Windows")) "javap.exe" else "javap"
    )
    val process = new ProcessBuilder(
      tool.toString,
      "-c",
      "-p",
      "-classpath",
      output.toString,
      "bench.Retained$nativejmh$bootstrapper$"
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
      |import scala.benchmark.*
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
    val loader = new URLClassLoader(Array(output.toUri.toURL), getClass.getClassLoader)
    try {
      val suite = loader
        .loadClass("bench.Example$nativejmh$bootstrapper$")
        .getField("MODULE$")
        .get(null)
        .asInstanceOf[Suite]
      val descriptor = Descriptor.parse(0, suite.metadata().head)
      val results = Execution.trial(
        suite,
        descriptor,
        Mode.SingleShotTime,
        descriptor.configuration.copy(synchIterations = false),
        Map.empty
      )
      assertEquals(3, results.size)
      assertTrue(results.flatMap(_.workers).forall(_.invocations == 5))
    } finally loader.close()
  }

  @Test def removedBenchmarksReplaceGeneratedMetadata(): Unit = compile(
    "package bench; import org.openjdk.jmh.annotations._; class Example { @Benchmark def run(): Int = 1 }"
  ) { (success, output) =>
    assertTrue(success)
    val source = output.getParent.resolve("Benchmark.scala")
    for (
      definition <- List(
        "class Example { def run(): Int = 1 }",
        "abstract class Example { @Benchmark def run(): Int = 1 }",
        "object Example { def run(): Int = 1 }"
      )
    ) {
      Files.write(
        source,
        ("package bench; import org.openjdk.jmh.annotations._; " + definition)
          .getBytes(StandardCharsets.UTF_8)
      )
      val reporter = dotty.tools.dotc.Main.process(
        Array(
          "-classpath",
          output.toString + java.io.File.pathSeparator + System.getProperty(
            "scalanative.jmh.testClasspath"
          ),
          "-d",
          output.toString,
          "-Xplugin:" + System.getProperty("scalanative.jmh.plugin"),
          source.toString
        )
      )
      assertFalse(reporter.hasErrors)
      val loader =
        new URLClassLoader(Array(output.toUri.toURL), getClass.getClassLoader)
      try {
        val suite = loader
          .loadClass("bench.Example$nativejmh$bootstrapper$")
          .getField("MODULE$")
          .get(null)
          .asInstanceOf[Suite]
        assertTrue(suite.metadata().isEmpty)
      } finally loader.close()
    }
  }

  private def inventory(output: Path, cls: String)(check: Array[String] => Unit): Unit = {
    val loader = new URLClassLoader(Array(output.toUri.toURL), getClass.getClassLoader)
    try
      check(
        loader
          .loadClass("bench." + cls + "$nativejmh$bootstrapper$")
          .getField("MODULE$")
          .get(null)
          .asInstanceOf[scala.benchmark.internal.BenchmarkInventory]
          .metadata()
      )
    finally loader.close()
  }

  @Test def platformRestrictionsPrecedeHarnessValidation(): Unit = {
    val source = """package bench
      |import scala.benchmark.*
      |@Platforms(Array(Platform.JVM), reason = "Requires grouped workers")
      |class Example {
      |  @Benchmark @Group("group") def run(): Int = 1
      |}
      |""".stripMargin
    for (backend <- List(Nil, List("backend=js"), List("backend=jvm")))
      compile(source, backend) { (success, output) =>
        assertTrue(success)
        inventory(output, "Example") { metadata =>
          val descriptor = Descriptor.parse(0, metadata.head)
          assertEquals(Vector(scala.benchmark.Platform.JVM), descriptor.eligibility.allowed)
          assertEquals(Vector("Requires grouped workers"), descriptor.eligibility.reasons)
          if (!backend.contains("backend=jvm")) assertTrue(descriptor.states.isEmpty)
        }
      }
  }

  @Test def platformInheritanceIntersectsOverrides(): Unit = compile(
    """package bench
      |import scala.benchmark.*
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
      assertEquals(Vector(scala.benchmark.Platform.Native), Descriptor.parse(0, metadata.head).eligibility.allowed)
    }
  }

  @Test def emptyPlatformsAreDiagnosedOnEveryBackend(): Unit = {
    for (
      backend <- List(Nil, List("backend=js"), List("backend=jvm"));
      restriction <- List("Array.empty[Platform]", "Array(Platform.JS)")
    )
      compile(
        s"""package bench
        |import scala.benchmark.*
        |@Platforms(Array(Platform.Native)) class Example {
        |  @Benchmark @Platforms($restriction) def run(): Int = 1
        |}
        |""".stripMargin,
        backend
      )((ok, _) => assertFalse(ok))
  }

  @Test def inheritedMethodCannotHaveEmptyEffectivePlatforms(): Unit = {
    for (backend <- List(Nil, List("backend=js"), List("backend=jvm")))
      compile(
        """package bench
        |import scala.benchmark.*
        |abstract class Base {
        |  @Benchmark @Platforms(Array(Platform.Native)) def run(): Int = 1
        |}
        |@Platforms(Array(Platform.JVM)) class Example extends Base
        |""".stripMargin,
        backend
      )((ok, _) => assertFalse(ok))
  }

  @Test def wasmOnlyHarnessSurvivesSharedJsCompilation(): Unit = compile(
    """package bench
      |import scala.benchmark.*
      |class Example {
      |  @Benchmark @Platforms(Array(Platform.Wasm)) def run(): Int = 1
      |}
      |""".stripMargin,
    List("backend=js")
  ) { (success, output) =>
    assertTrue(success)
    inventory(output, "Example") { metadata =>
      val descriptor = Descriptor.parse(0, metadata.head)
      assertEquals(Vector(scala.benchmark.Platform.Wasm), descriptor.eligibility.allowed)
      assertTrue(descriptor.states.nonEmpty)
    }
  }

  @Test def dynamicPlatformArraysAreRejected(): Unit = {
    for (expression <- List("Array(Platform.Native, choose())", "Array(Platform.Native) ++ Array(Platform.JS)"))
      compile(s"""package bench
        |import scala.benchmark.*
        |object Config { def choose(): Platform = Platform.JS }
        |import Config.*
        |@Platforms($expression) class Example { @Benchmark def run(): Int = 1 }
        |""".stripMargin)((ok, _) => assertFalse(ok))
  }

  @Test def incompatibleBodiesStillTypecheck(): Unit = compile(
    """package bench
      |import scala.benchmark.*
      |@Platforms(Array(Platform.JVM)) class Example {
      |  @Benchmark def run(): Int = "wrong"
      |}
      |""".stripMargin,
    List("backend=js")
  )((success, _) => assertFalse(success))

  @Test def eligibilityEditsReplaceIncrementalMetadata(): Unit = compile(
    """package bench
      |import scala.benchmark.*
      |@Platforms(Array(Platform.Native), reason = "first")
      |class Example { @Benchmark def run(): Int = 1 }
      |""".stripMargin,
    List("backend=js")
  ) { (success, output) =>
    assertTrue(success)
    inventory(output, "Example") { text =>
      assertEquals(Vector(scala.benchmark.Platform.Native), Descriptor.parse(0, text.head).eligibility.allowed)
    }
    val source = output.getParent.resolve("Benchmark.scala")
    Files.write(
      source,
      """package bench
      |import scala.benchmark.*
      |@Platforms(Array(Platform.JS), reason = "second")
      |class Example { @Benchmark def renamed(): Int = 1 }
      |""".stripMargin.getBytes(StandardCharsets.UTF_8)
    )
    val reporter = dotty.tools.dotc.Main.process(
      Array(
        "-classpath",
        System.getProperty("scalanative.jmh.testClasspath"),
        "-d",
        output.toString,
        "-Xplugin:" + System.getProperty("scalanative.jmh.plugin"),
        "-P:scalanative-jmh:backend=js",
        source.toString
      )
    )
    assertFalse(reporter.hasErrors)
    inventory(output, "Example") { text =>
      assertEquals(1, text.length)
      val descriptor = Descriptor.parse(0, text.head)
      assertEquals("bench.Example.renamed", descriptor.name)
      assertEquals(Vector(scala.benchmark.Platform.JS), descriptor.eligibility.allowed)
      assertEquals(Vector("second"), descriptor.eligibility.reasons)
      assertTrue(descriptor.states.nonEmpty)
    }
  }

  @Test def auxiliaryCountersCaptureIterationAndTrialTeardown(): Unit = {
    val relative = "sbt-plugin/src/sbt-test-sources/scala/scala/scalanative/jmh/examples/CounterBenchmark.scala"
    val source =
      Vector(java.nio.file.Paths.get(relative), java.nio.file.Paths.get("../" + relative)).find(Files.exists(_)).get
    compile(new String(Files.readAllBytes(source), StandardCharsets.UTF_8)) { (success, output) =>
      assertTrue(success)
      val loader = new URLClassLoader(Array(output.toUri.toURL), getClass.getClassLoader)
      try {
        val suite = loader
          .loadClass("scala.scalanative.jmh.examples.CounterBenchmark$nativejmh$bootstrapper$")
          .getField("MODULE$")
          .get(null)
          .asInstanceOf[Suite]
        val descriptor = Descriptor.parse(0, suite.metadata().head)
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
        for (format <- Reports.formats) assertTrue(Reports.render(Vector(result), format).contains("events"))
      } finally loader.close()
    }
  }

  @Test def auxiliaryCounterScopesAreValidated(): Unit = compile(
    """package bench
      |import scala.benchmark.*
      |@State(Scope.Benchmark) @AuxCounters class Counters { def count(): Long = 1 }
      |class Example { @Benchmark def run(c: Counters): Int = 1 }
      |""".stripMargin
  )((ok, _) => assertFalse(ok))

  @Test def groupedWorkersShareStatesAndRetainMemberMetrics(): Unit = {
    val relative = "sbt-plugin/src/sbt-test-sources/scala/scala/scalanative/jmh/examples/GroupBenchmark.scala"
    val source =
      Vector(java.nio.file.Paths.get(relative), java.nio.file.Paths.get("../" + relative)).find(Files.exists(_)).get
    compile(new String(Files.readAllBytes(source), StandardCharsets.UTF_8)) { (success, output) =>
      assertTrue(success)
      val loader = new URLClassLoader(Array(output.toUri.toURL), getClass.getClassLoader)
      try {
        val suite = loader
          .loadClass("scala.scalanative.jmh.examples.GroupBenchmark$nativejmh$bootstrapper$")
          .getField("MODULE$")
          .get(null)
          .asInstanceOf[Suite]
        val descriptor = Descriptor.parse(0, suite.metadata().head)
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
        for (format <- Reports.formats)
          assertTrue(Reports.render(Vector(result), format).contains("read"))
      } finally loader.close()
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

  @Test def shadowedPrivateParametersUseTheirDefiningClass(): Unit = compile(
    """package bench
      |import org.openjdk.jmh.annotations._
      |@State(Scope.Thread) class Parent {
      |  @Param(Array("7")) private var x: Int = 0
      |  @Setup def parentCheck(): Unit = assert(x == 7)
      |}
      |@State(Scope.Thread) class Child extends Parent {
      |  @Param(Array("7")) private var x: Long = 0L
      |  @Setup def childCheck(): Unit = assert(x == 7L)
      |}
      |class Example { @Benchmark def run(child: Child): Int = 1 }
      |""".stripMargin
  ) { (success, output) =>
    assertTrue(success)
    val loader =
      new URLClassLoader(Array(output.toUri.toURL), getClass.getClassLoader)
    try {
      val suite = loader
        .loadClass("bench.Example$nativejmh$bootstrapper$")
        .getField("MODULE$")
        .get(null)
        .asInstanceOf[Suite]
      val descriptor =
        Descriptor.parse(0, suite.metadata()(0), Some(Mode.SingleShotTime))
      Execution.trial(
        suite,
        descriptor,
        Mode.SingleShotTime,
        descriptor.configuration,
        Map("x" -> "7")
      )
    } finally loader.close()
  }

  @Test def jvmOnlyForkSettingsAreRecorded(): Unit = compile(
    """package bench
      |import org.openjdk.jmh.annotations._
      |@Fork(jvmArgs = Array("-Xmx2g"))
      |class Example { @Benchmark def run(): Int = 1 }
      |""".stripMargin
  ) { (success, output) =>
    assertTrue(success)
    val loader =
      new URLClassLoader(Array(output.toUri.toURL), getClass.getClassLoader)
    try {
      val suite = loader
        .loadClass("bench.Example$nativejmh$bootstrapper$")
        .getField("MODULE$")
        .get(null)
        .asInstanceOf[Suite]
      val descriptor = Descriptor.parse(0, suite.metadata()(0))
      assertTrue(descriptor.warnings.exists(_.contains("Fork.jvmArgs=-Xmx2g")))
    } finally loader.close()
  }

  @Test def parameterBridgesSurviveSeparateCompilation(): Unit = compile(
    """package bench
      |import org.openjdk.jmh.annotations._
      |@State(Scope.Thread) class Input {
      |  @Param(Array("7")) private var size: Int = 0
      |  @Setup def check(): Unit = assert(size == 7)
      |}
      |""".stripMargin
  ) { (success, output) =>
    assertTrue(success)
    val source = output.getParent.resolve("Second.scala")
    Files.write(
      source,
      "package bench; import org.openjdk.jmh.annotations._; class Example { @Benchmark def run(input: Input): Int = 1 }"
        .getBytes(StandardCharsets.UTF_8)
    )
    val reporter = dotty.tools.dotc.Main.process(
      Array(
        "-classpath",
        output.toString + java.io.File.pathSeparator + System.getProperty(
          "scalanative.jmh.testClasspath"
        ),
        "-d",
        output.toString,
        "-Xplugin:" + System.getProperty("scalanative.jmh.plugin"),
        source.toString
      )
    )
    assertFalse(reporter.hasErrors)
    val loader =
      new URLClassLoader(Array(output.toUri.toURL), getClass.getClassLoader)
    try {
      val suite = loader
        .loadClass("bench.Example$nativejmh$bootstrapper$")
        .getField("MODULE$")
        .get(null)
        .asInstanceOf[Suite]
      val descriptor =
        Descriptor.parse(0, suite.metadata()(0), Some(Mode.SingleShotTime))
      Execution.trial(
        suite,
        descriptor,
        Mode.SingleShotTime,
        descriptor.configuration,
        Map("size" -> "7")
      )
    } finally loader.close()
  }
}
