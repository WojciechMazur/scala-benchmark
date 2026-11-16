import scala.benchmark.sbtplugin.ScalaJSBenchmarkPlugin
import scala.scalanative.build.LTO
import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport.nativeConfig

import org.scalajs.linker.interface.ESVersion
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSLinkerConfig
import pl.project13.scala.sbt.JmhPlugin
import pl.project13.scala.sbt.JmhPlugin.JmhKeys.Jmh
import sbt.{*, given}
import sbt.Keys.*

object FixtureBuild:
  private val testScalaVersion = sys.props("benchmark.scala.version")
  private val repository = file(sys.props("benchmark.scripted.support")).getParentFile
  private val sharedSources = file(sys.props("benchmark.test.sources"))

  /** Benchmark sources that only some scripted tests use. */
  val fixtures: File = repository / "test/fixtures"

  val allPlatforms: Seq[String] = Seq("jvm", "native", "js", "wasm")

  /** Platforms under test: `-Dbenchmark.platforms=jvm,native`. The JVM runs first, as the reference. */
  val selectedPlatforms: Seq[String] =
    val requested = sys.props.get("benchmark.platforms").fold(Seq("jvm", "native", "js"))(_.split(",").toSeq)
    require(requested.forall(allPlatforms.contains), s"Unknown platform in: ${requested.mkString(",")}")
    allPlatforms.filter(requested.contains)

  val fixturePlatform = settingKey[String]("Platform exercised by this row of the benchmark fixture")

  /** Wasm shares the Scala.js platform; a second row needs an axis of its own. */
  private case object WasmAxis extends VirtualAxis.WeakAxis:
    val idSuffix = "Wasm"
    val directorySuffix = "wasm"

  private def platformSettings(platform: String): Seq[Setting[?]] = platform match
    case "jvm" =>
      Seq(
        Jmh / javaOptions += "-Djmh.ignoreLock=true",
        Jmh / run / forkOptions := Def.uncached {
          (Jmh / run / forkOptions).value.withWorkingDirectory(baseDirectory.value)
        }
      )
    case "native" =>
      Seq(nativeConfig ~= (_.withLTO(LTO(sys.env.getOrElse("SCALANATIVE_LTO", "none")))))
    case "js"   => Nil
    case "wasm" =>
      Seq(
        moduleName := "benchmarks-wasm",
        scalaJSLinkerConfig ~= (_.withESFeatures(_.withESVersion(ESVersion.ES2022).withUseWebAssembly(true)))
      )

  /** One project per platform under test, all compiling the same sources.
    *
    * Every row runs in a directory of its own, so that result files of different platforms do not collide.
    *
    * @param supported
    *   platforms this test applies to
    * @param settings
    *   sources and checks of the test, given the row's platform
    */
  def matrix(supported: String*)(settings: String => Seq[Setting[?]]): ProjectMatrix =
    val versions = Seq(testScalaVersion)
    def row(platform: String): Seq[Setting[?]] = Seq(
      fixturePlatform := platform,
      baseDirectory := {
        val directory = (LocalRootProject / baseDirectory).value / "run" / platform
        IO.createDirectory(directory)
        directory
      },
      libraryDependencies +=
        sys.props("plugin.organization") %% "scala-benchmark-interface" % sys.props("plugin.version"),
      // Sources stay where a single-platform project would keep them.
      sourceDirectory := (LocalRootProject / baseDirectory).value / "benchmarks/src",
      Compile / unmanagedSourceDirectories += sharedSources
    ) ++ platformSettings(platform) ++ settings(platform) ++
      Seq(Compile / sources := (Compile / sources).value.filterNot(isOtherHost(platform)))
    val base = ProjectMatrix("benchmarks", file("benchmarks"), getClass.getClassLoader).enablePlugins(JmhPlugin)
    selectedPlatforms.filter(supported.contains).foldLeft(base) {
      case (matrix, "jvm")    => matrix.jvmPlatform(versions, row("jvm"))
      case (matrix, "native") => matrix.nativePlatform(versions, row("native"))
      case (matrix, "js")     => matrix.jsPlatform(versions, row("js"))
      // The second Scala.js row needs an axis of its own, which only a custom row can declare.
      case (matrix, platform) =>
        matrix.customRow(
          versions,
          Seq(VirtualAxis.js, WasmAxis),
          (project: Project) => project.enablePlugins(ScalaJSPlugin).settings(row(platform))
        )
    }

  /** The repository checkout. */
  val root: File = repository.getParentFile.getParentFile

  /** Results of the JVM reference and of the platforms compared with it, kept for CI artifacts. */
  val evidence: File = root / "target/execution-parity"

  /** Observer checks shared with the runtime's unit tests. */
  val trialObserverChecks: File = root /
    "runtime/shared/src/test/scala/scala/benchmark/runtime/TrialObserverChecks.scala"

  def sha256(path: File): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(IO.readBytes(path)).map(b => f"${b & 0xff}%02x").mkString

  /** Fixture directory with the host services of a platform: processes or the Node.js host. */
  def host(platform: String): String = if platform == "jvm" || platform == "native" then "process" else "js"

  /** Fixture directories are added recursively; sources for the other kind of host must stay out. */
  private def isOtherHost(platform: String)(source: File): Boolean =
    val other = if host(platform) == "process" then "js" else "process"
    source.getParentFile.getName == other

  /** Preloads a module into every Node.js process of the benchmark. */
  def nodeImport(module: File): Seq[Setting[?]] =
    Seq(ScalaJSBenchmarkPlugin.autoImport.benchmarkNodeOptions := Seq("--import", module.getAbsolutePath))

  /** Command line that runs the row's benchmarks without sbt: upstream JMH, the linked executable, or Node.js with
    * the linked module. The benchmarks must have been run through sbt before, so that they are linked.
    */
  def benchmarkCommand(platform: String): Def.Initialize[Task[Vector[String]]] = Def.task {
    val files = (target.value ** "*").get().filter(_.isFile)
    def artifact(description: String)(predicate: File => Boolean): String =
      files.find(predicate).getOrElse(sys.error("Missing " + description)).getAbsolutePath
    platform match
      case "jvm" =>
        val converter = fileConverter.value
        val classpath = (Jmh / fullClasspath).value.map(entry => converter.toPath(entry.data).toString)
        Vector(
          (file(sys.props("java.home")) / "bin/java").getAbsolutePath,
          "-Djmh.ignoreLock=true",
          "-classpath",
          classpath.mkString(java.io.File.pathSeparator),
          "org.openjdk.jmh.Main"
        )
      case "native" =>
        Vector(artifact("linked executable")(f => f.canExecute && Set("benchmarks", "benchmarks.exe")(f.getName)))
      case _ =>
        Vector(
          "node",
          artifact("Node.js host")(_.getName == "node-host.mjs"),
          artifact("linked module")(f => f.getName == "main.js" && f.getPath.contains("-opt/"))
        )
  }

  /** Restricts compilation to the named source files. */
  def only(names: String*): Seq[Setting[?]] =
    Seq(Compile / sources := (Compile / sources).value.filter(source => names.contains(source.getName)))

  /** Adds directories below [[fixtures]] to the sources. */
  def fixtureDirectories(paths: String*): Seq[Setting[?]] =
    Seq(Compile / unmanagedSourceDirectories ++= paths.map(fixtures / _))

  // ---------------------------------------------------------------------------------------------------------------
  // Script commands
  // ---------------------------------------------------------------------------------------------------------------

  private val SucceededMarker = "fixtureCommandSucceeded"

  private def rows(state: State): Seq[(String, String)] =
    val structure = Project.extract(state).structure
    val found = structure.allProjectRefs.flatMap { reference =>
      (reference / fixturePlatform).get(structure.data).map(_ -> reference.project)
    }
    selectedPlatforms.flatMap(platform => found.find(_._1 == platform))

  /** Runs a command to completion and reports whether it succeeded, without ending the script on failure. */
  private def succeeds(state: State, command: String): (State, Boolean) =
    val isolated = state.copy(
      remainingCommands = List(Exec(SucceededMarker, None), Exec(BasicCommandStrings.FailureWall, None)),
      onFailure = None
    )
    val result = Command.process(command, isolated, message => state.log.error(message))
    val succeeded = result.remainingCommands.headOption.exists(_.commandLine == SucceededMarker)
    (result.copy(remainingCommands = state.remainingCommands, onFailure = state.onFailure), succeeded)

  /** Expands the placeholders of a script command for one row:
    *   - `@` is the project id; a command without it is a key of the project
    *   - `{platform}` is the row's platform
    *   - `{other}` is a platform the row does not run on
    */
  private def scoped(platform: String, project: String, command: String): String =
    val other = if platform == "js" then "wasm" else "js"
    val expanded = command.replace("{platform}", platform).replace("{other}", other)
    if expanded.contains("@") then expanded.replace("@", project) else s"$project/$expanded"

  private def runOn(state: State, platforms: Option[Set[String]], command: String, expectSuccess: Boolean): State =
    rows(state)
      .filter { case (platform, _) => platforms.forall(_.contains(platform)) }
      .foldLeft(Option(state)) {
        case (Some(current), (platform, project)) =>
          val (next, succeeded) = succeeds(current, scoped(platform, project, command))
          if succeeded == expectSuccess then Some(next)
          else
            val outcome = if succeeded then "succeeded" else "failed"
            current.log.error(s"[$platform] `$command` unexpectedly $outcome")
            None
        case (None, _) => None
      }
      .getOrElse(state.fail)

  private def selective(name: String, expectSuccess: Boolean): Command =
    Command.single(name) { (state, arguments) =>
      val Array(platforms, command) = arguments.trim.split("\\s+", 2)
      runOn(state, Option.unless(platforms == "all")(platforms.split(",").toSet), command, expectSuccess)
    }

  /** Commands of the test scripts.
    *
    *   - `bench <command>` runs the command on every platform under test
    *   - `on <platforms> <command>` runs it on the listed platforms that are under test
    *   - `failing <platforms|all> <command>` requires it to fail on each of them
    */
  val commands: Seq[Setting[?]] = Seq(
    Keys.commands ++= Seq(
      Command.single("bench")((state, command) => runOn(state, None, command.trim, expectSuccess = true)),
      selective("on", expectSuccess = true),
      selective("failing", expectSuccess = false),
      Command.command(SucceededMarker)(identity)
    )
  )

  private val generatedEntryPoint = Def.task {
    (Jmh / compile).value
    (Jmh / sourceManaged).value / "BenchmarkMain.scala"
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

  /** The generated entry point refers to exactly the benchmark classes that currently exist. */
  def checkGenerated(entryPoint: File, name: String, present: Boolean): Unit =
    assert(
      IO.read(entryPoint).contains("suite[_root_.probe." + name + "]") == present,
      s"probe.$name present != $present"
    )

  def settings(backend: String): Seq[Setting[?]] = Seq(
    verifyDependencies := Def.uncached {
      val converter = fileConverter.value
      val names = (Compile / dependencyClasspath).value.map(entry =>
        converter.toPath(entry.data).getFileName.toString
      )
      assert(names.exists(_.startsWith("jmh-core")) == (backend == "jvm"))
      if backend != "jvm" then assert(!names.exists(_.startsWith("jmh-generator")))
    },
    verifyWarmupAndOutput := Def.uncached {
      val directory = baseDirectory.value
      for mode <- Vector("INDI", "BULK", "BULK_INDI") do
        val result = ujson.read(IO.read(directory / s"bulk-$mode.json")).arr
        assert(result.size == 1 && result.head("benchmark").str.endsWith("BulkWarmupBenchmark.execute"))
        assert(result.head("warmupIterations").num == (if mode == "BULK" then 0 else 1))
        assert(result.head("primaryMetric")("rawData").arr.head.arr.size == 1)
      val human = IO.read(directory / "human.log")
      assert(human.contains("PlatformBenchmarks.everywhere"))
      assert(IO.read(directory / "quiet.log").isEmpty)
    },
    verifyExactSelection := Def.uncached {
      val directory = baseDirectory.value
      val result = ujson.read(IO.read(directory / "exact.json")).arr
      assert(result.map(_("benchmark").str).toVector == Vector("literal.bench.CanonicalBenchmark.execute"))
    },
    verifyCounterResults := Def.uncached {
      val directory = baseDirectory.value
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
      val directory = baseDirectory.value
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
    },
    verifyEligibility := Def.uncached {
      val prefix = "scala.benchmark.examples.PlatformBenchmarks."
      val expected = Set(prefix + "everywhere", prefix + backend + "Only")
      val results = ujson.read(IO.read(baseDirectory.value / "eligibility.json")).arr
      assert(results.map(_("benchmark").str).toSet == expected)
      assert(results.size == expected.size)
    },
    verifyResults := Def.uncached {
      val directory = baseDirectory.value
      val results = ujson.read(IO.read(directory / "results.json")).arr
      assert(results.map(_("mode").str).toSet == Set("thrpt", "avgt", "sample", "ss"))
      results.foreach { result =>
        assert(result("threads").num == (if backend == "native" then 2 else 1) && result("forks").num == 2)
        assert(result("params")("size").str == "16")
        assert(result(if backend == "native" then "native" else "javascript")("scalaVersion").str == testScalaVersion)
        if backend != "native" then
          assert(result("javascript")("backend").str == backend)
          assert(result("javascript")("engine").str == "V8")
        val metric = result("primaryMetric")
        assert(metric("score").num.isFinite && metric("score").num >= 0)
        if result("mode").str != "ss" then assert(metric("score").num > 0)
        if result("mode").str == "sample" then assert(metric("rawDataHistogram").arr.size == 2)
        else assert(metric("rawData").arr.map(_.arr.size).toSeq == Seq(3, 3))
      }
      val csv = IO.readLines(directory / "results.csv")
      assert(csv.size == 2 && csv.head.contains("Param: size") && csv(1).contains("16"))
    },
    verifyAdditionalReports := Def.uncached {
      val directory = baseDirectory.value
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
    writeFirst := Def.uncached(writeProbe((LocalRootProject / baseDirectory).value, "First")),
    writeSecond := Def.uncached(writeProbe((LocalRootProject / baseDirectory).value, "Second")),
    removeProbe := Def.uncached(IO.delete(probe((LocalRootProject / baseDirectory).value))),
    verifyFirst := Def.uncached(checkGenerated(generatedEntryPoint.value, "First", present = true)),
    verifyRenamed := Def.uncached {
      checkGenerated(generatedEntryPoint.value, "First", present = false)
      checkGenerated(generatedEntryPoint.value, "Second", present = true)
    },
    verifyRemoved := Def.uncached {
      for name <- Seq("First", "Second") do checkGenerated(generatedEntryPoint.value, name, present = false)
    }
  )

end FixtureBuild
