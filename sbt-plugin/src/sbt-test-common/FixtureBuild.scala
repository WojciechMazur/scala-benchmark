import sbt.{*, given}
import sbt.Keys.*
import pl.project13.scala.sbt.JmhPlugin

object FixtureBuild {
  private val testScalaVersion = sys.props("benchmark.scala.version")

  def matrix(backend: String): ProjectMatrix = {
    val base = ProjectMatrix("benchmarks", file("benchmarks"), getClass.getClassLoader)
    val matrix = backend match {
      case "jvm"         => base.jvmPlatform(Seq(testScalaVersion))
      case "native"      => base.nativePlatform(Seq(testScalaVersion))
      case "js" | "wasm" => base.jsPlatform(Seq(testScalaVersion))
      case other         => sys.error(s"Unknown scripted backend: $other")
    }
    matrix
      .enablePlugins(JmhPlugin)
      .settings(
        libraryDependencies += "org.scala-native" %% "benchmark-interface" % sys.props("plugin.version"),
        Compile / unmanagedSourceDirectories += file(sys.props("benchmark.test.sources"))
      )
  }

  val verifyDependencies = taskKey[Unit]("Check platform dependencies")
  val verifyPreflight = taskKey[Unit]("Ensure an incompatible exact request wrote no partial results")
  val verifyWarmupAndOutput = taskKey[Unit]("Check warmup-only selection and human-readable output flags")
  val verifyExactSelection = taskKey[Unit]("Canonical include names stay literal through upstream delegation")
  val verifyCounterResults = taskKey[Unit]("Compare auxiliary counter observations with upstream JMH semantics")
  val verifyGroupResults = taskKey[Unit]("Verify grouped primary and member observations")
  val verifyEligibility = taskKey[Unit]("Check results against generated platform eligibility metadata")
  val verifyResults = taskKey[Unit]("Validate Native JSON and CSV measurements")
  val verifyAdditionalReports = taskKey[Unit]("Check text, SCSV and LaTeX result files")
  val writeFirst = taskKey[Unit]("Add an incremental benchmark")
  val writeSecond = taskKey[Unit]("Rename an incremental benchmark")
  val removeProbe = taskKey[Unit]("Delete an incremental benchmark")
  val verifyFirst = taskKey[Unit]("Verify initial products")
  val verifyRenamed = taskKey[Unit]("Verify renamed products")
  val verifyRemoved = taskKey[Unit]("Verify deleted products")
  def probe(root: File) = root / "benchmarks/src/main/scala/IncrementalProbe.scala"
  def writeProbe(root: File, name: String): Unit =
    IO.write(probe(root), s"package probe; import scala.benchmark.*; class $name { @Benchmark def run(): Int = 1 }")
  def checkProduct(classes: File, inventory: File, name: String, present: Boolean, backend: String): Unit = {
    val module = name + "$nativejmh$bootstrapper$"
    assert(IO.read(inventory).contains("probe." + module) == present)
    for (extension <- Seq(".class", if (backend == "native") ".nir" else ".sjsir"))
      assert((classes / "probe" / (module + extension)).exists() == present)
  }

  def settings(benchmark: Project, backend: String): Seq[Setting[?]] = Seq(
    verifyDependencies := Def.uncached {
      val converter = fileConverter.value
      val names = (benchmark / Compile / dependencyClasspath).value.map(entry =>
        converter.toPath(entry.data).getFileName.toString
      )
      assert(names.exists(_.startsWith("jmh-core")) == (backend == "jvm"))
      if (backend != "jvm") assert(!names.exists(_.startsWith("jmh-generator")))
    },
    verifyWarmupAndOutput := Def.uncached {
      val directory = if (backend == "jvm") baseDirectory.value else (benchmark / baseDirectory).value
      for (mode <- Vector("INDI", "BULK", "BULK_INDI")) {
        val result = ujson.read(IO.read(directory / s"bulk-$mode.json")).arr
        assert(result.size == 1 && result.head("benchmark").str.endsWith("BulkWarmupBenchmark.execute"))
        assert(result.head("warmupIterations").num == (if (mode == "BULK") 0 else 1))
        assert(result.head("primaryMetric")("rawData").arr.head.arr.size == 1)
      }
      val human = IO.read(directory / "human.log")
      assert(human.contains("PlatformBenchmarks.everywhere"))
      assert(IO.read(directory / "quiet.log").isEmpty)
    },
    verifyExactSelection := Def.uncached {
      val directory = if (backend == "jvm") baseDirectory.value else (benchmark / baseDirectory).value
      val result = ujson.read(IO.read(directory / "exact.json")).arr
      assert(result.map(_("benchmark").str).toVector == Vector("literal.bench.CanonicalBenchmark.execute"))
    },
    verifyCounterResults := Def.uncached {
      val directory = if (backend == "jvm") baseDirectory.value else (benchmark / baseDirectory).value
      val result = ujson.read(IO.read(directory / "counters.json")).arr.head
      val metrics = result("secondaryMetrics").obj
      assert(metrics.keySet == Set("events"))
      val events = metrics("events")
      assert(events("score").num == 23 && events("scoreUnit").str == "#")
      assert(events("rawData").arr.head.arr.map(_.num).toVector == Vector(9.0, 14.0))
      assert(events("scoreConfidence").arr.map(_.num).toVector == Vector(23.0, 23.0))
      val modes = ujson.read(IO.read(directory / "counter-modes.json")).arr
      assert(modes.map(_("mode").str).toSet == Set("thrpt", "avgt", "sample", "ss"))
      modes.foreach { row =>
        val names = row("secondaryMetrics").obj.keySet
        assert(names.contains("events"))
        assert(names.contains("operations") == Set("thrpt", "avgt")(row("mode").str))
      }
    },
    verifyGroupResults := Def.uncached {
      val directory = if (backend == "jvm") baseDirectory.value else (benchmark / baseDirectory).value
      val result = ujson.read(IO.read(directory / "groups.json")).arr.toVector
      assert(result.size == 1)
      assert(result.head("benchmark").str.endsWith("GroupBenchmark.exchange"))
      assert(result.head("threads").num == 6)
      val secondary = result.head("secondaryMetrics").obj
      assert(secondary.keySet == Set("read", "write"))
      secondary.values.foreach { metric =>
        assert(metric("score").num.isFinite && metric("score").num >= 0)
        assert(metric("rawData").arr.size == 1 && metric("rawData").arr.head.arr.size == 2)
      }
    },
    verifyPreflight := Def.uncached {
      assert(!(baseDirectory.value / "preflight.json").exists())
      assert(!((benchmark / baseDirectory).value / "preflight.json").exists())
    },
    verifyEligibility := Def.uncached {
      val converter = fileConverter.value
      val classpath = (benchmark / Compile / fullClasspath).value.map(e => converter.toPath(e.data).toUri.toURL)
      val loader = new java.net.URLClassLoader(classpath.toArray, null)
      val expected = try {
        val module = loader
          .loadClass("scala.scalanative.jmh.examples.PlatformBenchmarks$nativejmh$bootstrapper$")
          .getField("MODULE$")
          .get(null)
        val metadata = module.getClass.getMethod("metadata").invoke(module).asInstanceOf[Array[String]]
        val identity = Map("jvm" -> "JVM", "native" -> "Native", "js" -> "JS", "wasm" -> "Wasm")(backend)
        metadata.toVector.flatMap { text =>
          val properties = new java.util.Properties
          properties.load(new java.io.StringReader(text))
          Option.when(properties.getProperty("platforms").split(",").contains(identity))(properties.getProperty("name"))
        }.toSet
      } finally loader.close()
      val directory = if (backend == "jvm") baseDirectory.value else (benchmark / baseDirectory).value
      val results = ujson.read(IO.read(directory / "eligibility.json")).arr
      assert(results.map(_("benchmark").str).toSet == expected)
      assert(results.size == expected.size)
    },
    verifyResults := Def.uncached {
      val directory = (benchmark / baseDirectory).value
      val results = ujson.read(IO.read(directory / "results.json")).arr
      assert(results.map(_("mode").str).toSet == Set("thrpt", "avgt", "sample", "ss"))
      results.foreach { result =>
        assert(result("threads").num == (if (backend == "native") 2 else 1) && result("forks").num == 2)
        assert(result("params")("size").str == "16")
        assert(result(if (backend == "native") "native" else "javascript")("scalaVersion").str == testScalaVersion)
        if (backend != "native") {
          assert(result("javascript")("backend").str == backend)
          assert(result("javascript")("engine").str == "V8")
        }
        val metric = result("primaryMetric")
        assert(metric("score").num.isFinite && metric("score").num >= 0)
        if (result("mode").str != "ss") assert(metric("score").num > 0)
        if (result("mode").str == "sample") assert(metric("rawDataHistogram").arr.size == 2)
        else assert(metric("rawData").arr.map(_.arr.size).toSeq == Seq(3, 3))
      }
      val csv = IO.readLines(directory / "results.csv")
      assert(csv.size == 2 && csv.head.contains("Param: size") && csv(1).contains("16"))
    },
    verifyAdditionalReports := Def.uncached {
      val directory = (benchmark / baseDirectory).value
      val scsv = IO.readLines(directory / "results.scsv")
      assert(scsv.size == 2 && scsv.head.startsWith("\"Benchmark\";\"Mode\";"))
      assert(scsv(1).contains("FacadeBenchmark.execute") && scsv(1).endsWith(";\"7\""))
      val text = IO.read(directory / "results.txt")
      assert(text.contains("FacadeBenchmark.execute") && text.contains("Percentiles") && text.contains("Param: size"))
      assert(!text.contains("NaN"))
      val latex = IO.read(directory / "results.tex")
      assert(latex.startsWith("\\begin{tabular}") && latex.endsWith("\\end{tabular}\n"))
      assert(latex.contains("FacadeBenchmark.execute") && latex.contains("99.9\\%"))
    },
    writeFirst := Def.uncached { writeProbe(baseDirectory.value, "First") },
    writeSecond := Def.uncached { writeProbe(baseDirectory.value, "Second") },
    removeProbe := Def.uncached { IO.delete(probe(baseDirectory.value)) },
    verifyFirst := Def.uncached {
      checkProduct(
        (benchmark / Compile / classDirectory).value,
        (benchmark / Compile / resourceManaged).value /
          (if (backend == "native") "native-jmh/benchmarks.list" else "scala-benchmark/manifest.json"),
        "First",
        true,
        backend
      )
    },
    verifyRenamed := Def.uncached {
      val classes = (benchmark / Compile / classDirectory).value
      val inventory = (benchmark / Compile / resourceManaged).value /
        (if (backend == "native") "native-jmh/benchmarks.list" else "scala-benchmark/manifest.json")
      checkProduct(classes, inventory, "First", false, backend)
      checkProduct(classes, inventory, "Second", true, backend)
    },
    verifyRemoved := Def.uncached {
      val classes = (benchmark / Compile / classDirectory).value
      val inventory = (benchmark / Compile / resourceManaged).value /
        (if (backend == "native") "native-jmh/benchmarks.list" else "scala-benchmark/manifest.json")
      for (name <- Seq("First", "Second")) checkProduct(classes, inventory, name, false, backend)
    }
  )
}
