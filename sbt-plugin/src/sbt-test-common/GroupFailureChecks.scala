import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import pl.project13.scala.sbt.JmhPlugin.JmhKeys.*
import sbt.{*, given}
import sbt.Keys.*

object GroupFailureChecks:
  val verifyGroupFailures = taskKey[Unit]("Check grouped failures against upstream JMH behavior")

  def settings(backend: String): Seq[Setting[?]] = Seq(
    verifyGroupFailures := Def.uncached {
      val directory = baseDirectory.value
      val targetDirectory = target.value
      val converter = fileConverter.value
      val classpath = (Jmh / fullClasspath).value.map(e => converter.toPath(e.data).toString)
      val executable = java.nio.file.Paths
        .get(sys.props("java.home"), "bin", if sys.props("os.name").startsWith("Windows") then "java.exe" else "java")
      val command =
        if backend == "jvm" then
          Vector(
            executable.toString,
            "-Djmh.ignoreLock=true",
            "-classpath",
            classpath.mkString(java.io.File.pathSeparator),
            "org.openjdk.jmh.Main"
          )
        else
          Vector(
            (targetDirectory ** "*")
              .get()
              .find(f => f.isFile && f.canExecute && Set("benchmarks", "benchmarks.exe")(f.getName))
              .get
              .getAbsolutePath
          )
      val repository = file(sys.props("benchmark.scripted.support")).getParentFile.getParentFile.getParentFile
      val evidence = repository / "target/execution-parity/group-failures" / backend
      IO.delete(evidence); IO.createDirectory(evidence)
      val smoke = Vector(directory / "smoke.json", baseDirectory.value / "smoke.json")
        .find(_.isFile)
        .getOrElse(sys.error("Missing grouped smoke results"))
      val smokeResults = ujson.read(IO.read(smoke)).arr
      assert(smokeResults.size == 1 && smokeResults.head("benchmark").str.endsWith("GroupFailureBenchmark.pair"))
      IO.copyFile(smoke, evidence / "smoke.json")
      if backend == "native" then IO.copyFile(file(command.head), evidence / "benchmark-executable")
      (targetDirectory ** "*.java").get().filter(_.getPath.contains("jmh")).foreach { source =>
        val relative = IO.relativize(targetDirectory, source).get
        IO.copyFile(source, evidence / "generated" / relative)
      }
      var observations = Vector.empty[ujson.Value]
      def probe(
          stage: String,
          mode: String,
          threads: Int,
          sync: Boolean,
          warmup: Int,
          foe: Boolean = true,
          stubborn: Boolean = false,
          embedded: Boolean = false,
          late: Boolean = false,
          batch: Int = 1,
          opi: Int = 1
      ): Unit =
        val id = s"$stage-$mode-t$threads-si$sync-w$warmup-foe$foe-stubborn$stubborn-late$late-b$batch-opi$opi"
        val log = evidence / (id + ".log")
        val report = evidence / (id + ".json")
        val include = if foe then "GroupFailureBenchmark" else "Group(Failure|Recovery)Benchmark"
        val args = Vector(
          include,
          "-p",
          "boundary=" + stage,
          "-p",
          "behavior=" + (if stubborn then "stubborn" else "cooperative"),
          "-p",
          "failAtIteration=" + (if late then "2" else "1"),
          "-bs",
          batch.toString,
          "-wbs",
          batch.toString,
          "-opi",
          opi.toString,
          "-bm",
          mode,
          "-t",
          threads.toString,
          "-si",
          sync.toString,
          "-wi",
          warmup.toString,
          "-i",
          "2",
          "-f",
          if embedded then "0" else "1",
          "-w",
          "5ms",
          "-r",
          "5ms",
          "-to",
          "500ms",
          "-foe",
          foe.toString,
          "-rf",
          "json",
          "-rff",
          report.getAbsolutePath
        )
        val started = System.nanoTime()
        val process = new ProcessBuilder((command ++ args).asJava)
          .directory(directory)
          .redirectErrorStream(true)
          .redirectOutput(log)
          .start()
        var descendants = Vector.empty[ProcessHandle]
        try
          while process.isAlive && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(20) do
            descendants = (descendants ++ process.toHandle.descendants().iterator().asScala).distinct
            process.waitFor(10, TimeUnit.MILLISECONDS)
          assert(!process.isAlive, id + ": outer watchdog expired; " + IO.read(log))
          assert(
            process.exitValue() == (if foe || stubborn then 1 else 0),
            id + ": unexpected exit " + process.exitValue() + ": " + IO.read(log)
          )
          assert(descendants.forall(!_.isAlive), id + ": surviving fork descendants")
          val text = IO.read(log)
          val events =
            "GROUP_EVENT\\t[0-9]+\\t[0-9]+\\t[^\\t\\r\\n]+\\tEND".r.findAllIn(text).map(_.stripSuffix("\tEND")).toVector
          val failures = events.filter(_.endsWith("failure:" + stage))
          assert(failures.size == 1, id + ": expected one failing shared fixture/member, got " + failures + "\n" + text)
          assert(text.contains("injected group failure: " + stage), id + ": primary cause was lost")
          if late then
            assert(events.exists(_.endsWith("iteration:2")), id + ": late fault did not reach the second iteration")
          if stage == "invoke" then
            assert(events.exists(_.endsWith("peerEntered")), id + ": peer did not run")
            assert(events.exists(_.endsWith("peerReleased")) != stubborn, id + ": unexpected peer release")
          val results =
            if report.exists() && IO.read(report).trim.nonEmpty then ujson.read(IO.read(report)).arr.toVector
            else Vector.empty
          assert(
            results.forall(_("benchmark").str.endsWith("GroupRecoveryBenchmark.execute")),
            id + ": failed group emitted measurements"
          )
          assert(results.size == (if foe || stubborn then 0 else 1), id + ": successful recovery result missing")
          if !foe && !stubborn then assert(text.contains("GROUP_RECOVERY"), id + ": later benchmark never ran")
          if stubborn then assert(!text.contains("GROUP_RECOVERY"), id + ": contaminated process ran recovery work")
          observations :+= ujson.Obj(
            "case" -> id,
            "exit" -> process.exitValue(),
            "events" -> ujson.Arr.from(events),
            "measurements" -> results.size
          )
        finally
          descendants.filter(_.isAlive).foreach(_.destroyForcibly())
          if process.isAlive then process.destroyForcibly()
          process.waitFor(2, TimeUnit.SECONDS)
        end try
      end probe
      for
        stage <- Vector(
          "trialSetup",
          "iterationSetup",
          "invocationSetup",
          "invoke",
          "invocationTeardown",
          "iterationTeardown",
          "trialTeardown",
          "counter"
        )
      do
        probe(stage, "ss", 2, false, 0)
        probe(stage, "thrpt", 4, true, 1)
      for mode <- Vector("avgt", "sample"); sync <- Vector(false, true) do probe("invoke", mode, 2, sync, 0)
      probe("invoke", "ss", 2, false, 0, late = true)
      probe("counter", "ss", 2, false, 0, late = true)
      probe("iterationTeardown", "thrpt", 4, true, 0, late = true)
      probe("counter", "thrpt", 4, true, 0, late = true)
      probe("invoke", "ss", 2, false, 0, foe = false)
      probe("counter", "thrpt", 4, true, 0, foe = false)
      if backend == "native" then
        probe("invoke", "thrpt", 2, true, 0, stubborn = true)
        probe("invoke", "thrpt", 2, true, 0, foe = false, stubborn = true, embedded = true)
      for
        mode <- Vector("thrpt", "avgt");
        stage <- Vector("invocationSetup", "invocationTeardown", "iterationTeardown", "counter", "operations");
        batch <- Vector(1, 3); opi <- Vector(1, 7)
      do probe(stage, mode, 4, true, 1, batch = batch, opi = opi)
      for stage <- Vector("invoke", "invocationTeardown") do
        probe(stage, "ss", 4, false, 0, late = true, batch = 3, opi = 7)
      for mode <- Vector("ss", "sample") do
        val output = evidence / ("disabled-operations-" + mode + ".log")
        val report = evidence / ("disabled-operations-" + mode + ".json")
        val child = new ProcessBuilder(
          (command ++ Vector(
            "GroupFailureBenchmark",
            "-p",
            "boundary=operations",
            "-bm",
            mode,
            "-t",
            "2",
            "-wi",
            "0",
            "-i",
            "2",
            "-r",
            "5ms",
            "-f",
            "1",
            "-bs",
            "3",
            "-opi",
            "7",
            "-foe",
            "true",
            "-rf",
            "json",
            "-rff",
            report.getAbsolutePath
          )).asJava
        ).directory(directory).redirectErrorStream(true).redirectOutput(output).start()
        try
          assert(child.waitFor(20, TimeUnit.SECONDS), "Disabled counter probe hung")
          assert(child.exitValue() == 0, IO.read(output))
          assert(!IO.read(output).contains("operationsRead"), "Disabled OPERATIONS getter executed in " + mode)
          val rows = ujson.read(IO.read(report)).arr
          assert(rows.size == 1 && !rows.head("secondaryMetrics").obj.contains("operations"))
        finally
          if child.isAlive then
            child.toHandle.descendants().iterator().asScala.foreach(_.destroyForcibly()); child.destroyForcibly();
            child.waitFor(2, TimeUnit.SECONDS)
      end for
      val apiLog = evidence / "programmatic-recovery.log"
      val api = new ProcessBuilder(
        (command ++ Vector("GroupApiRecoveryBenchmark", "-f", "0", "-wi", "0", "-i", "1", "-foe", "true")).asJava
      )
        .directory(directory)
        .redirectErrorStream(true)
        .redirectOutput(apiLog)
        .start()
      try
        assert(api.waitFor(20, TimeUnit.SECONDS), "Programmatic recovery exceeded watchdog")
        assert(api.exitValue() == 0 && IO.read(apiLog).contains("GROUP_API_RECOVERY"), IO.read(apiLog))
      finally
        if api.isAlive then
          api.destroyForcibly(); api.waitFor(2, TimeUnit.SECONDS)
      IO.write(evidence / "observations.json", ujson.write(ujson.Arr.from(observations), indent = 2))
      val files = (Compile / sources).value ++
        (Compile / dependencyClasspath).value
          .map(e => converter.toPath(e.data).toFile)
          .filter(f => f.getName.startsWith("benchmark-") || f.getName.startsWith("jmh-")) ++
        (if backend == "native" then Vector(file(command.head)) else Vector.empty)
      IO.write(
        evidence / "provenance.json",
        ujson.write(
          ujson.Obj(
            "backend" -> backend,
            "os" -> sys.props("os.name"),
            "scala" -> scalaVersion.value,
            "jdk" -> sys.props("java.runtime.version"),
            "artifacts" -> ujson.Arr.from(
              files.map(f =>
                ujson.Obj(
                  "file" -> f.getName,
                  "sha256" ->
                    java.security.MessageDigest
                      .getInstance("SHA-256")
                      .digest(IO.readBytes(f))
                      .map(b => f"${b & 0xff}%02x")
                      .mkString
                )
              )
            )
          ),
          indent = 2
        )
      )
    }
  )

end GroupFailureChecks
