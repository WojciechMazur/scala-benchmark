package scala.scalanative.jmh

import org.junit.Assert._
import org.junit.Test

import org.apache.commons.math3.distribution.TDistribution
import org.openjdk.jmh.annotations.Mode

class HarnessTest {
  @Test def symmetricWorkersHaveIndividualGroups(): Unit = {
    val params = Execution.workerParams(3, 8)
    assertEquals(3, params.getGroupIndex())
    assertEquals(8, params.getGroupCount())
    assertEquals(0, params.getGroupThreadIndex())
    assertEquals(1, params.getGroupThreadCount())
    assertEquals(1, params.getSubgroupThreadCount())
  }
  @Test def studentTMatchesJmhDistribution(): Unit = {
    for (df <- Vector(1L, 2L, 3L, 5L, 10L, 30L, 100L, 1000L, 100000L, 10000000L)) {
      val expected =
        new TDistribution(df.toDouble).inverseCumulativeProbability(0.9995)
      assertEquals(s"df=$df", expected, StudentT.quantile(0.9995, df), 1e-7)
    }
    val stats = Statistics(Vector(1.0 -> 1L, 2.0 -> 1L, 3.0 -> 1L))
    assertEquals(2.0, stats.mean, 0.0)
    assertEquals(1.0, stats.variance, 0.0)
    assertTrue(Statistics(Vector(1.0 -> 1L)).error.isNaN)
    assertTrue(Statistics(Vector(1.0 -> 1L, 2.0 -> 1L)).error.isNaN)
  }

  @Test def quantizedHistogramAndHalving(): Unit = {
    val buffer = new SampleBuffer
    buffer.addCount(1023, 3)
    buffer.addCount(2049, 4)
    buffer.add(Long.MaxValue)
    assertTrue(buffer.entries.contains(2048L -> 4L))
    buffer.half()
    assertTrue(buffer.entries.contains(1023L -> 1L))
    assertTrue(buffer.entries.contains(2048L -> 2L))
    assertEquals(1L, buffer.entries.last._2)
    val merged = new SampleBuffer
    merged.merge(buffer); merged.merge(buffer)
    assertEquals(buffer.entries.map(_._2).sum * 2, merged.entries.map(_._2).sum)
  }

  @Test def modeSpecificNormalization(): Unit = {
    val c = Configuration(
      measurementBatchSize = 4,
      operationsPerInvocation = 2,
      unit = "NANOSECONDS"
    )
    val first = RawResult(20, 1000, new SampleBuffer)
    val second = RawResult(40, 1000, new SampleBuffer)
    assertEquals(
      0.03,
      Execution.aggregate(Mode.Throughput, c, Vector(first, second)),
      1e-12
    )
    assertEquals(
      75.0,
      Execution.aggregate(Mode.AverageTime, c, Vector(first, second)),
      1e-12
    )
    // A SingleShot operation is the whole batch, normalized only by OPI.
    assertEquals(
      500.0,
      Execution.aggregate(Mode.SingleShotTime, c, Vector(first, second)),
      1e-12
    )
  }

  @Test def generatedLoopTemplatesCountAndExcludeFixtures(): Unit = {
    for (
      mode <- Vector(
        Mode.Throughput,
        Mode.AverageTime,
        Mode.SingleShotTime,
        Mode.SampleTime
      )
    ) {
      val control = new IterationControl(mode, 7, 3, 1000000L)
      var calls = 0
      var setups = 0
      var teardowns = 0
      var active = false
      val result = Loops.run(
        control,
        new SamplingState,
        true, {
          assertTrue(active)
          calls += 1
          if (calls >= 7) control.done = true
        },
        { assertFalse(active); active = true; setups += 1 }, {
          assertTrue(active); active = false; teardowns += 1
        }
      )
      assertEquals(7, calls)
      assertEquals(if (mode == Mode.SampleTime) 1 else 7, setups)
      assertEquals(setups, teardowns)
      assertFalse(active)
      assertEquals(7L, result.invocations)
      assertTrue(result.elapsedNanos > 0)
      if (mode == Mode.SampleTime)
        assertEquals(1L, result.samples.entries.map(_._2).sum)
    }
  }

  @Test def metadataEscapesAndParameterProduct(): Unit = {
    val descriptor = Descriptor.parse(
      0,
      "name=example.test\nmodes=SingleShotTime\nstates=0\nstate.0.scope=Thread\nstate.0.params=x,y\n" +
        "state.0.param.x.count=2\nstate.0.param.x.0=\nstate.0.param.x.1=a,b\\ntext\n" +
        "state.0.param.y.count=2\nstate.0.param.y.0=1\nstate.0.param.y.1=2\n"
    )
    assertEquals(0, descriptor.configuration.warmupIterations)
    assertEquals(1, descriptor.configuration.measurementIterations)
    val combinations = Descriptor.combinations(descriptor, Map.empty)
    assertEquals(4, combinations.size)
    assertTrue(combinations.exists(_("x") == ""))
    assertTrue(combinations.exists(_("x") == "a,b\ntext"))
    assertEquals(
      2,
      Descriptor.combinations(descriptor, Map("y" -> Vector("3"))).size
    )
  }

  @Test def commandLineValidation(): Unit = {
    val options = Options.parse(
      Array(
        "example.*",
        "-bm",
        "ss",
        "-t",
        "max",
        "-p",
        "size=1,2",
        "-r",
        "500us",
        "-tu",
        "ns"
      )
    )
    val c = options.configuration(Configuration())
    assertEquals(500000L, c.measurementNanos)
    assertEquals("NANOSECONDS", c.unit)
    assertTrue(c.threads > 0)
    assertEquals(Vector("1", "2"), options.parameters("size"))
    for (
      arguments <- Vector(
        Array("-i", "0"),
        Array("-r", "0.5ms"),
        Array("-t", "-2"),
        Array("-t", "-3")
      )
    ) {
      try {
        Options.parse(arguments).configuration(Configuration());
        fail("Invalid option accepted")
      } catch { case _: IllegalArgumentException => () }
    }
  }

  @Test def portableParsingAgreesWithUpstream(): Unit = {
    import scala.jdk.CollectionConverters.*
    val arguments = Array(
      "bench.*",
      "-bm",
      "thr",
      "-bm",
      "ss",
      "-p",
      "size=1,2",
      "-p",
      "size=3",
      "-tu",
      "US",
      "-w",
      "0s",
      "-r",
      "7us",
      "-to",
      "5s",
      "-t",
      "max",
      "-si",
      "unknown",
      "-gc",
      "true"
    )
    val upstream = new org.openjdk.jmh.runner.options.CommandLineOptions(arguments*)
    val portable = Options.parse(arguments)
    val config = portable.configuration(Configuration())
    assertEquals(upstream.getBenchModes().asScala.toSet, portable.modes.toSet)
    assertEquals(upstream.getParameter("size").get().asScala.toVector, portable.parameters("size"))
    assertEquals(upstream.getTimeUnit().get().name(), config.unit)
    assertEquals(upstream.getWarmupTime().get(), Configuration.time(config, true))
    assertEquals(upstream.getMeasurementTime().get(), Configuration.time(config, false))
    assertEquals(upstream.getTimeout().get(), Configuration.timeout(config))
    assertEquals(upstream.shouldSyncIterations().get().booleanValue(), config.synchIterations)
    assertEquals(upstream.shouldDoGC().get().booleanValue(), config.doGC)
    for (
      invalid <- Vector(Array("-i", "1", "-i", "2"), Array("-tu", "d"), Array("-tu", "SECONDS"), Array("-r", "0.5ms"))
    ) {
      assertTrue(scala.util.Try(new org.openjdk.jmh.runner.options.CommandLineOptions(invalid*)).isFailure)
      assertTrue(scala.util.Try(Options.parse(invalid)).isFailure)
    }
  }

  @Test def originalTimeUnitsSurviveForkConfiguration(): Unit = {
    val configured = Options.parse(Array("-w", "2ms", "-r", "7us", "-to", "5s")).configuration(Configuration())
    val transported = Options(overrides = Configuration.overrides(configured)).configuration(Configuration())
    assertEquals(org.openjdk.jmh.runner.options.TimeValue.milliseconds(2), Configuration.time(transported, true))
    assertEquals(org.openjdk.jmh.runner.options.TimeValue.microseconds(7), Configuration.time(transported, false))
    assertEquals(org.openjdk.jmh.runner.options.TimeValue.seconds(5), Configuration.timeout(transported))
    assertEquals(org.openjdk.jmh.runner.options.TimeValue.minutes(10), Configuration.timeout(Configuration()))
    val annotated = Descriptor.parse(
      0,
      "name=timeout.test\nmodes=SingleShotTime\ntimeoutNanos=3000000\ntimeoutTimeValue=3\ntimeoutTimeUnit=MILLISECONDS\n"
    )
    assertEquals(
      org.openjdk.jmh.runner.options.TimeValue.milliseconds(3),
      Configuration.timeout(annotated.configuration)
    )
  }

  @Test def reportFormatsPreserveAndEscapeParameters(): Unit = {
    val descriptor =
      Descriptor.parse(0, "name=bench.Example_test.run\nmodes=AverageTime\nstates=0\nstate.0.scope=Thread\n")
    val value = "a;b,\"c\"\n\\{}%&#$_~^"
    val run = RunResult(
      descriptor,
      Mode.AverageTime,
      Configuration(unit = "NANOSECONDS"),
      Map("input_name" -> value),
      Vector(Vector(2.0, 4.0, 6.0).map(score => IterationResult(score, Vector.empty))),
      ClockCalibration(1, 1.0)
    )
    val results = Vector(run)
    val scsv = Reports.render(results, "scsv")
    assertTrue(scsv.startsWith("\"Benchmark\";\"Mode\";"))
    assertTrue(scsv.contains("\"a;b,\"\"c\"\"\n\\{}%&#$_~^\""))
    assertTrue(Reports.render(results, "csv").startsWith("\"Benchmark\",\"Mode\","))
    val latex = Reports.render(results, "latex")
    assertTrue(latex.startsWith("\\begin{tabular}{llrrrrll}\n"))
    assertTrue(latex.contains("Example\\_test.run"))
    assertTrue(latex.contains("input\\_name"))
    assertTrue(latex.contains("Score Error (99.9\\%)"))
    assertTrue(
      latex.contains("""a;b,"c"\textbackslash{}n\textbackslash{}\{\}\%\&\#\$\_\textasciitilde{}\textasciicircum{}""")
    )
    assertTrue(latex.linesIterator.filter(_.contains(" & ")).forall(_.endsWith(" \\\\")))
    assertTrue(latex.endsWith("\\end{tabular}\n"))
    val text = Reports.render(results, "text")
    assertTrue(text.contains("Example_test.run") && text.contains("Param: input_name"))
    assertTrue(text.endsWith("\n"))
    for (format <- Vector("text", "scsv", "latex"))
      assertEquals(format, Options.parse(Array("-rf", format.toUpperCase)).format)
    try { Options.parse(Array("-rf", "xml")); fail("Unknown report format accepted") }
    catch { case _: IllegalArgumentException => () }
  }

  @Test def htmlReportEscapesUntrustedContent(): Unit = {
    import scala.benchmark.internal.reporting.HtmlReport
    val unsafe = """</pre><script>alert("x")</script><img src=x onerror='bad'>&東京"""
    val entry = HtmlReport.Entry(
      unsafe,
      "avgt",
      4.0,
      2.0,
      Vector(2.0, 6.0),
      "ns/op",
      3,
      Vector(unsafe -> unsafe),
      Vector("50.0" -> 4.0),
      Vector(unsafe -> unsafe),
      unsafe
    )
    val html = HtmlReport.render(Vector(entry))
    val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    val document = factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(html)))
    assertEquals(0, document.getElementsByTagName("script").getLength)
    assertEquals(0, document.getElementsByTagName("img").getLength)
    assertEquals(unsafe, document.getElementsByTagName("pre").item(0).getTextContent)
    assertTrue(document.getDocumentElement.getTextContent.contains(unsafe))
    assertTrue(html.contains("4.0") && html.contains("[2.0, 6.0]"))
    assertEquals("html", Options.parse(Array("-rf", "HTML")).format)
  }

  @Test def resultProtocolRoundTrip(): Unit = {
    val path = java.nio.file.Files.createTempFile("native-jmh-test", ".bin")
    try {
      val samples = new SampleBuffer
      samples.addCount(100, 9)
      val results = Vector(
        IterationResult(4.5, Vector(RawResult(7, 1234, samples)))
      )
      Main.writeResults(path, results)
      val decoded = Main.readResults(path)
      assertEquals(4.5, decoded.head.score, 0.0)
      assertEquals(7L, decoded.head.workers.head.invocations)
      assertEquals(samples.entries, decoded.head.workers.head.samples.entries)
      java.nio.file.Files.write(path, Array[Byte](1, 2, 3))
      try { Main.readResults(path); fail("Truncated result accepted") }
      catch { case _: java.io.EOFException => () }
    } finally java.nio.file.Files.deleteIfExists(path)
  }

  private class FailingSuite extends Suite {
    def metadata(): Array[String] = Array.empty
    def instantiate(id: Int): AnyRef = new Object
    def assign(
        id: Int,
        target: AnyRef,
        parameter: String,
        value: String
    ): Unit = ()
    def setup(id: Int, level: Int, target: AnyRef, env: Environment): Unit = ()
    def teardown(id: Int, level: Int, target: AnyRef, env: Environment): Unit =
      ()
    def measure(
        id: Int,
        env: Environment,
        control: IterationControl
    ): RawResult = {
      if (env.threadParams.getThreadIndex() == 0)
        throw new IllegalStateException("benchmark failure")
      Loops.run(control, env.sampling, false, (), (), ())
    }
  }
  @Test def workerFailureReleasesPeers(): Unit = {
    val descriptor = Descriptor(
      0,
      "failure",
      Vector(Mode.Throughput),
      Configuration(),
      Vector(StateDefinition(0, false, Vector.empty, Vector.empty)),
      Vector.empty
    )
    val c = Configuration(
      warmupIterations = 0,
      measurementIterations = 1,
      measurementNanos = 10000000L,
      threads = 2,
      timeoutNanos = 1000000000L
    )
    try {
      Execution.trial(
        new FailingSuite,
        descriptor,
        Mode.Throughput,
        c,
        Map.empty
      );
      fail("Worker failure was lost")
    } catch {
      case e: IllegalStateException =>
        assertEquals("benchmark failure", e.getMessage)
    }
  }

  @Test def singleShotBelowClockResolutionIsAValidZero(): Unit = {
    val suite = new FailingSuite {
      override def measure(
          id: Int,
          env: Environment,
          control: IterationControl
      ): RawResult =
        RawResult(1L, 0L, new SampleBuffer)
    }
    val c = Configuration(
      warmupIterations = 0,
      measurementIterations = 3,
      timeoutNanos = 1000000000L
    )
    val descriptor = Descriptor(
      0,
      "sub-resolution",
      Vector(Mode.SingleShotTime),
      c,
      Vector(StateDefinition(0, false, Vector.empty, Vector.empty)),
      Vector.empty
    )
    val results =
      Execution.trial(suite, descriptor, Mode.SingleShotTime, c, Map.empty)
    assertEquals(Vector(0.0, 0.0, 0.0), results.map(_.score))
  }

  @Test def timedLoopsAlwaysPerformAnInvocation(): Unit = {
    for (mode <- Vector(Mode.Throughput, Mode.AverageTime, Mode.SampleTime)) {
      val control = new IterationControl(mode, 1, 1, 1L)
      control.done = true
      var calls = 0
      val result =
        Loops.run(control, new SamplingState, false, { calls += 1 }, (), ())
      assertEquals(1, calls)
      assertEquals(1L, result.invocations)
    }
  }
}
