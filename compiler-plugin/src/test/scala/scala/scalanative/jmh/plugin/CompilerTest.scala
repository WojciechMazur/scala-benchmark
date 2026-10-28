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
  private def compile(source: String)(check: (Boolean, Path) => Unit): Unit = {
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
        )
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

  @Test def sourceCompatibleHarnessExecutesDirectCallsAndFixtures(): Unit =
    compile(
      """package bench
        |import org.openjdk.jmh.annotations._
        |import org.openjdk.jmh.infra.Blackhole
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

  @Test def unsupportedGroupsAreDiagnosed(): Unit = compile(
    """package bench
      |import org.openjdk.jmh.annotations._
      |class Example { @Benchmark @Group("group") def run(): Int = 1 }
      |""".stripMargin
  )((success, _) => assertFalse(success))

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
