import scala.sys.process.*

import sbt.{*, given}
import sbt.Keys.*

object IntegrityChecks:

  /** Node.js preload that records what the host observes of the optimized workload. */
  val observeHost: File = file(sys.props("benchmark.scripted.support")).getParentFile /
    "test/integrity/observe-host.mjs"

  val verifyIntegrity = taskKey[Unit]("Retain and inspect optimized measurement artifacts")

  def settings(backend: String): Seq[Setting[?]] = Seq(
    verifyIntegrity := Def.uncached {
      val directory = baseDirectory.value
      val repository = FixtureBuild.root
      val rows = ujson.read(IO.read(directory / "integrity.json")).arr
      val variant = if backend == "native" then backend + "-" + rows.head("native")("lto").str else backend
      val evidence = repository / "target/execution-parity/integrity" / variant
      IO.delete(evidence)
      IO.createDirectory(evidence)
      val methods = Vector(
        "primitiveBoolean",
        "primitiveByte",
        "primitiveShort",
        "primitiveChar",
        "primitiveInt",
        "inlinedInt",
        "primitiveLong",
        "primitiveFloat",
        "primitiveDouble",
        "returnedAllocation",
        "explicitBlackhole",
        "observableUnit"
      )
      assert(rows.map(_("benchmark").str.split('.').last).toSet == methods.toSet)
      rows.foreach { row =>
        assert(row("mode").str == "ss" && row("measurementBatchSize").num == 7)
        assert(row("primaryMetric")("score").num.isFinite)
        assert(row("primaryMetric")("rawData").arr.size == 1)
        assert(row("primaryMetric")("rawData").arr.head.arr.size == 1)
      }
      IO.copyFile(directory / "integrity.json", evidence / "results.json")
      val targetDirectory = target.value
      val converter = fileConverter.value
      val dependencies = (Compile / dependencyClasspath).value
        .map(e => converter.toPath(e.data).toFile)
        .filter(file => file.isFile && file.getName.startsWith("benchmark-"))
      val fixtureSources = (Compile / sources).value
      val options = (Compile / scalacOptions).value
      val plugins = options
        .filter(_.startsWith("-Xplugin:"))
        .flatMap(_.stripPrefix("-Xplugin:").split(java.io.File.pathSeparator))
        .map(file)
        .filter(_.isFile)
      def hash(path: File): ujson.Value = ujson.Obj(
        "file" -> path.getName,
        "sha256" ->
          FixtureBuild.sha256(path)
      )
      val artifacts = (targetDirectory ** "*").get().filter(_.isFile)
      if backend == "native" then
        val executable = artifacts
          .find(f => f.canExecute && f.getName == "benchmarks")
          .getOrElse(sys.error("Missing Native executable under " + targetDirectory))
        IO.copyFile(executable, evidence / "benchmark-executable")
        val tool = sys.env.getOrElse("LLVM_OBJDUMP", "llvm-objdump")
        val disassembly = Process(Seq(tool, "--disassemble", "--demangle", executable.getAbsolutePath)).!!
        val blocks = disassembly.split("(?m)(?=^[0-9a-f]+ <[^\\n]+>:$)")
        IO.write(
          evidence / "disassembly.txt",
          blocks
            .filter { block =>
              val header = block.linesIterator.nextOption().getOrElse("")
              header.contains("IntegrityBenchmark") || header.contains("BenchmarkMain") ||
              header.contains("scala_benchmark_consume") || header.contains(
                "scalanative_nano_time"
              )
            }
            .mkString
        )
        methods
          .filterNot(_ == "inlinedInt")
          .foreach(name => assert(disassembly.contains(name), "Optimized workload missing: " + name))
        assert(disassembly.contains("scala_benchmark_consume"), "Missing primitive consumption barrier")
        assert(disassembly.contains("scala_benchmark_consume_reference"), "Missing reference consumption barrier")
        val ir = artifacts.filter(_.getName.endsWith(".ll"))
        val definitions = "(?ms)^define[^\\n]*(?:IntegrityBenchmark|BenchmarkMain|Blackhole)[^\\n]*\\{.*?^\\}".r
        val functions = ir.flatMap(path => definitions.findAllIn(IO.read(path)).toVector)
        assert(functions.nonEmpty, "No optimized benchmark LLVM functions retained")
        IO.write(evidence / "optimized-functions.ll", functions.mkString("\n\n"))
        val dumps = artifacts.filter(_.getName.endsWith(".hnir"))
        dumps.foreach(path =>
          IO.write(
            evidence / path.getName,
            IO.read(path)
              .split("(?m)(?=^[^ %}][^\\n]*(?:def |class |module |trait |const |var ))")
              .filter(block => block.linesIterator.nextOption().exists(_.contains("IntegrityBenchmark")))
              .mkString("\n")
          )
        )
        val inspector =
          file(sys.props("benchmark.scripted.support")).getParentFile / "test/integrity/inspect-native.mjs"
        assert(
          Process(Seq("node", inspector.getAbsolutePath, evidence.getAbsolutePath)).! == 0,
          "Native optimized-code inspection failed"
        )
      else
        val unobserved = ujson.read(IO.read(directory / "integrity-unobserved.json")).arr
        assert(unobserved.map(_("benchmark").str).toSet == rows.map(_("benchmark").str).toSet)
        assert(unobserved.forall(row => row("primaryMetric")("score").num.isFinite))
        IO.copyFile(directory / "integrity-unobserved.json", evidence / "unobserved-results.json")
        IO.copyFile(directory / "integrity-host.json", evidence / "host-observations.json")
        val linked = artifacts.filter(f =>
          f.getPath.contains("-opt/") &&
            (f.getName.endsWith(".js") || f.getName.endsWith(".wasm") ||
              f.getName
                .endsWith(".wat"))
        )
        assert(linked.nonEmpty, "Missing full-link output")
        linked.foreach(path => IO.copyFile(path, evidence / path.getName))
        artifacts
          .filter(path =>
            (path.getParentFile.getName == "scala-benchmark" && Set(
              "node-host.mjs",
              "progress-deadline.mjs",
              "worker-lifeline.mjs",
              "output-relay.mjs",
              "console-writer.mjs"
            )(
              path.getName
            )) ||
              (path.getParentFile.getName.endsWith("-opt") && path.getName == "package.json")
          )
          .foreach(path => IO.copyFile(path, evidence / path.getName))
        assert(
          (evidence / "node-host.mjs").isFile,
          "Missing runnable host evidence"
        )
        assert(linked.exists(_.getName.endsWith(".wasm")) == (backend == "wasm"))
        if backend == "wasm" then
          val inspector =
            file(sys.props("benchmark.scripted.support")).getParentFile / "test/integrity/inspect-wasm.mjs"
          assert(
            Process(Seq("node", inspector.getAbsolutePath, evidence.getAbsolutePath)).! == 0,
            "Wasm optimized-code inspection failed"
          )
      end if
      val retained = (evidence ** "*").get().filter(_.isFile).filterNot(_.getName == "provenance.json")
      IO.write(
        evidence / "provenance.json",
        ujson.write(
          ujson.Obj(
            "backend" -> backend,
            "scala" -> scalaVersion.value,
            "configuration" -> rows.head(if backend == "native" then "native" else "javascript"),
            "jdk" -> sys.props("java.runtime.version"),
            "os" -> sys.props("os.name"),
            "arch" -> sys.props("os.arch"),
            "artifacts" -> ujson.Arr.from(
              (fixtureSources ++ dependencies ++ plugins ++ retained ++
                Seq((LocalRootProject / baseDirectory).value / "build.sbt") ++
                Seq("IntegrityChecks.scala", "FixtureBuild.scala").map(name =>
                  file(sys.props("benchmark.scripted.support")) / name
                ) ++
                ((file(sys.props("benchmark.scripted.support")).getParentFile / "test/integrity") ** "*.mjs")
                  .get()).distinct.map(hash)
            )
          ),
          indent = 2
        )
      )
    }
  )

end IntegrityChecks
