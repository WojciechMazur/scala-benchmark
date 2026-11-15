import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import pl.project13.scala.sbt.JmhPlugin.JmhKeys.*
import sbt.{*, given}
import sbt.Keys.*

object ForkSequenceChecks:
  val verifyForkSequence = taskKey[Unit]("Compare warmup/later-fork failure retention with JMH")

  def settings(backend: String): Seq[Setting[?]] = Seq(
    verifyForkSequence := Def.uncached {
      val directory = baseDirectory.value
      val targetDirectory = target.value
      val files = (targetDirectory ** "*").get().filter(_.isFile)
      def artifact(name: String): File = files.find(_.getName == name).getOrElse(sys.error("Missing " + name))
      val converter = fileConverter.value
      val classpath = (Jmh / fullClasspath).value.map(e => converter.toPath(e.data).toString)
      val support = file(sys.props("benchmark.scripted.support"))
      val repository = FixtureBuild.root
      val hook = support.getParentFile / "test/fixtures/fork-sequence/journal-host.mjs"
      val command =
        if backend == "jvm" then
          Vector(
            java.nio.file.Paths
              .get(
                sys.props("java.home"),
                "bin",
                if sys.props("os.name").startsWith("Windows") then "java.exe" else "java"
              )
              .toString,
            "-Djmh.ignoreLock=true",
            "-classpath",
            classpath.mkString(java.io.File.pathSeparator),
            "org.openjdk.jmh.Main"
          )
        else if backend == "native" then
          Vector(files.find(f => f.canExecute && Set("benchmarks", "benchmarks.exe")(f.getName)).get.getAbsolutePath)
        else
          Vector(
            "node",
            "--import",
            hook.getAbsolutePath,
            artifact("node-host.mjs").getAbsolutePath,
            files.find(f => f.getName == "main.js" && f.getPath.contains("-opt/")).get.getAbsolutePath
          )
      val evidence = repository / "target/execution-parity/fork-sequence" / backend
      IO.delete(evidence); IO.createDirectory(evidence)
      var observations = Vector.empty[ujson.Value]
      def probe(id: String, boundary: String, fail: Int, warmups: Int, foe: Boolean, format: String = "json"): Unit =
        val ledger = evidence / (id + ".ledger")
        val report = evidence / (id + ".result." + format)
        val log = evidence / (id + ".log")
        val healthy = fail > warmups + 2
        val include = if healthy then "ASequenceBenchmark" else "(ASequenceBenchmark|ZSequenceRecoveryBenchmark)"
        val args = Vector(
          include,
          "-p",
          "ledger=" + ledger.getAbsolutePath,
          "-p",
          "failFork=" + fail,
          "-p",
          "boundary=" + boundary,
          "-bm",
          "ss",
          "-t",
          "1",
          "-wi",
          "0",
          "-i",
          "2",
          "-f",
          "2",
          "-wf",
          warmups.toString,
          "-to",
          "2s",
          "-foe",
          foe.toString,
          "-rf",
          if backend == "jvm" && format == "html" then "text" else format,
          "-rff",
          report.getAbsolutePath
        )
        val process = new ProcessBuilder((command ++ args).asJava)
          .directory(directory)
          .redirectErrorStream(true)
          .redirectOutput(log)
          .start()
        var children = Vector.empty[ProcessHandle]
        val started = System.nanoTime()
        try
          while process.isAlive && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(30) do
            children = (children ++ process.toHandle.descendants().iterator().asScala).distinct
            process.waitFor(10, TimeUnit.MILLISECONDS)
          assert(!process.isAlive, id + ": watchdog expired")
          val text = IO.read(log)
          assert(process.exitValue() == (if !healthy && foe then 1 else 0), id + ": " + text)
          assert(children.forall(!_.isAlive), id + ": fork survived")

          if !healthy then
            assert(
              text.contains(if boundary == "crash" then "23" else s"injected fork $fail $boundary"),
              id + ": lost primary cause"
            )
            assert(text.contains("FORK_RECOVERY") == !foe, id + ": incorrect continuation")
          val expected = if healthy then "ASequenceBenchmark.execute" else "ZSequenceRecoveryBenchmark.execute"
          var populations = Vector.empty[Int]
          var names = Vector.empty[String]
          var perBenchmark = Vector.empty[ujson.Value]
          if format == "json" then
            val results =
              if report.exists() && IO.read(report).trim.nonEmpty then ujson.read(IO.read(report)).arr.toVector
              else Vector.empty

            results.foreach { row =>
              names :+= row("benchmark").str
              populations = row("primaryMetric")("rawData").arr.map(_.arr.size).toVector
              perBenchmark :+= ujson.Obj(
                "benchmark" -> row("benchmark").str,
                "populations" -> ujson.Arr.from(populations)
              )
            }
          else
            val result = IO.read(report)
            assert(result.nonEmpty, id + ": empty " + format)
            val expectedNames = observations
              .find(_("case").str == "teardown-fork3-foefalse")
              .get("results")
              .arr
              .map(_("benchmark").str.split('.').takeRight(2).mkString("."))
              .toSet
            for name <- Vector("ASequenceBenchmark.execute", "ZSequenceRecoveryBenchmark.execute") do
              assert(result.contains(name) == expectedNames(name), id + ": incorrect retained benchmarks in " + format)
          observations :+= ujson.Obj(
            "case" -> id,
            "exit" -> process.exitValue(),
            "forksStarted" -> IO.read(ledger).toInt,
            "rawPopulations" -> ujson.Arr.from(populations),
            "format" -> format,
            "results" -> ujson.Arr.from(perBenchmark)
          )
        finally
          children.filter(_.isAlive).foreach(_.destroyForcibly())
          if process.isAlive then process.destroyForcibly()
          process.waitFor(2, TimeUnit.SECONDS)
        end try
      end probe
      probe("healthy", "work", 99, 1, true)
      for stage <- Vector("setup", "work", "teardown", "crash"); failure <- Vector(1, 3); foe <- Vector(false, true) do
        probe(s"$stage-fork$failure-foe$foe", stage, failure, 1, foe)
      for foe <- Vector(false, true) do probe(s"second-measurement-foe$foe", "work", 2, 0, foe)
      for format <- Vector("text", "csv", "scsv", "html", "latex") do
        probe("report-" + format, "teardown", 3, 1, false, format)
      val apiLedger = evidence / "api.ledger"
      val apiLog = evidence / "api.log"
      val api = new ProcessBuilder(
        (command ++ Vector(
          "SequenceApiBenchmark",
          "-p",
          "ledger=" + apiLedger.getAbsolutePath,
          "-f",
          "0",
          "-wf",
          "0",
          "-i",
          "1",
          "-wi",
          "0",
          "-foe",
          "true"
        )).asJava
      )
        .directory(directory)
        .redirectErrorStream(true)
        .redirectOutput(apiLog)
        .start()
      try
        assert(api.waitFor(30, TimeUnit.SECONDS), "API fork recovery watchdog expired")
        assert(api.exitValue() == 0 && IO.read(apiLog).contains("FORK_API_RECOVERY_VERIFIED"), IO.read(apiLog))
      finally
        if api.isAlive then
          api.toHandle.descendants().iterator().asScala.foreach(_.destroyForcibly()); api.destroyForcibly();
          api.waitFor(2, TimeUnit.SECONDS)
      if backend != "jvm" then
        val oracle =
          ujson.read(IO.read(evidence.getParentFile / "jvm/observations.json")).arr.map(r => r("case").str -> r).toMap
        observations.foreach(row =>
          assert(row == oracle(row("case").str), "Fork outcome differs from JMH: " + ujson.write(row))
        )
      IO.write(evidence / "observations.json", ujson.write(ujson.Arr.from(observations), indent = 2))
      if backend != "jvm" then
        val request = evidence / "transport-config.json"
        IO.write(
          request,
          ujson.write(
            ujson.Obj(
              "backend" -> backend,
              "command" -> ujson.Arr.from(command),
              "evidence" -> evidence.getAbsolutePath
            )
          )
        )
        val log = evidence / "transport-driver.log"
        val probe = new ProcessBuilder(
          "python3",
          (support.getParentFile / "test/transport/check-transports.py").getAbsolutePath,
          request.getAbsolutePath
        )
          .directory(directory)
          .redirectErrorStream(true)
          .redirectOutput(log)
          .start()
        try
          assert(probe.waitFor(90, TimeUnit.SECONDS), "Transport driver watchdog expired")
          assert(probe.exitValue() == 0, IO.read(log))
        finally
          if probe.isAlive then
            probe.toHandle.descendants().iterator().asScala.foreach(_.destroyForcibly())
            probe.destroyForcibly(); probe.waitFor(2, TimeUnit.SECONDS)
      val fixtureSources = (Compile / sources).value ++ Vector(
        support / "ForkSequenceChecks.scala",
        (LocalRootProject / baseDirectory).value / "build.sbt"
      )
      IO.write(
        evidence / "provenance.json",
        ujson.write(
          ujson.Obj(
            "backend" -> backend,
            "scala" -> scalaVersion.value,
            "os" -> sys.props("os.name"),
            "jdk" -> sys.props("java.runtime.version"),
            "files" -> ujson.Arr.from(
              fixtureSources.map(f =>
                ujson.Obj(
                  "file" -> f.getName,
                  "sha256" ->
                    FixtureBuild.sha256(f)
                )
              )
            )
          ),
          indent = 2
        )
      )
    }
  )

end ForkSequenceChecks
