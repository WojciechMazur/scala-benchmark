import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import sbt.{*, given}
import sbt.Keys.*

object ForkContainmentChecks:
  val verifyForkContainment = taskKey[Unit]("Inject fork faults under an independent outer watchdog")

  def settings(backend: String): Seq[Setting[?]] = Seq(
    verifyForkContainment := Def.uncached {
      val directory = baseDirectory.value
      val artifacts = (target.value ** "*").get().filter(_.isFile)
      def artifact(predicate: File => Boolean): File =
        artifacts.find(predicate).getOrElse(sys.error("Missing linked artifact"))
      val command =
        if backend == "native" then
          Vector(
            artifact(f => f.canExecute && (f.getName == "benchmarks" || f.getName == "benchmarks.exe")).getAbsolutePath
          )
        else
          Vector(
            "node",
            "--import",
            (file(
              sys.props("benchmark.scripted.support")
            ).getParentFile / "test/fixtures/containment/tree-host.mjs").getAbsolutePath,
            artifact(_.getName == "node-host.mjs").getAbsolutePath,
            artifact(f => f.getName == "main.js" && f.getPath.contains("-opt/")).getAbsolutePath
          )
      val repository = file(sys.props("benchmark.scripted.support")).getParentFile.getParentFile.getParentFile
      val evidence = repository / "target/execution-parity/containment" / backend
      IO.delete(evidence)
      IO.createDirectory(evidence)
      var observations = Vector.empty[ujson.Value]
      def probe(name: String, boundary: String, action: String, kill: String = "", warmup: Boolean = false): Unit =
        val marker = evidence / (name + ".marker")
        val report = evidence / (name + ".json")
        val log = evidence / (name + ".log")
        val args = Vector(
          "ForkContainmentBenchmark",
          "-p",
          "boundary=" + boundary,
          "-p",
          "action=" + action,
          "-p",
          "marker=" + marker.getAbsolutePath,
          "-wi",
          if warmup then "1" else "0",
          "-i",
          "1",
          "-w",
          "1ms",
          "-r",
          "1ms",
          "-to",
          if kill.nonEmpty then "10s" else "100ms",
          "-foe",
          "true",
          "-rf",
          "json",
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
        def within(seconds: Int): Boolean = System.nanoTime() - started < TimeUnit.SECONDS.toNanos(seconds)
        try
          while !marker.exists() && process.isAlive && within(10) do Thread.sleep(10)
          assert(marker.exists(), name + ": fixture did not reach fault boundary: " + IO.read(log))
          children = process.toHandle.descendants().iterator().asScala.toVector
          if kill == "owner" then process.destroyForcibly()
          else if kill == "worker" then
            val worker = children
              .find(child => !children.exists(_.parent().orElse(null) == child))
              .getOrElse(sys.error(name + ": missing fork worker"))
            assert(worker.destroyForcibly(), name + ": failed to kill worker")
          assert(process.waitFor(10, TimeUnit.SECONDS), name + ": coordinator exceeded outer watchdog")
          assert(process.exitValue() != 0, name + ": failed run reported success")
          while children.exists(_.isAlive) && within(15) do Thread.sleep(10)
          assert(
            !children.exists(_.isAlive),
            name + ": surviving descendants " + children.filter(_.isAlive).map(_.pid())
          )
          assert(
            !report.exists() || ujson.read(IO.read(report)).arr.isEmpty,
            name + ": failed configuration published a measurement"
          )
          observations :+= ujson.Obj(
            "case" -> name,
            "boundary" -> boundary,
            "exit" -> process.exitValue(),
            "children" -> ujson.Arr.from(children.map(_.pid())),
            "elapsedNanos" -> (System.nanoTime() - started).toString
          )
        finally
          children.filter(_.isAlive).foreach(_.destroyForcibly())
          if process.isAlive then process.destroyForcibly()
          process.waitFor(2, TimeUnit.SECONDS)
        end try
      end probe
      Vector(
        "trialSetup",
        "iterationSetup",
        "invocationSetup",
        "invoke",
        "invocationTeardown",
        "iterationTeardown",
        "trialTeardown"
      )
        .foreach(stage => probe("failure-" + stage, stage, "fail"))
      probe("warmup-failure", "invoke", "fail", warmup = true)
      probe("timeout", "invoke", "hang")
      probe("killed-worker", "invoke", "hang", "worker")
      Vector("trialSetup", "invoke", "trialTeardown").foreach(stage =>
        probe("killed-owner-" + stage, stage, "hang", "owner")
      )
      if !sys.props("os.name").startsWith("Windows") then
        val request = evidence / "process-tree-config.json"
        IO.write(
          request,
          ujson.write(ujson.Obj("command" -> ujson.Arr.from(command), "evidence" -> evidence.getAbsolutePath))
        )
        val log = evidence / "process-tree-driver.log"
        val helper =
          file(sys.props("benchmark.scripted.support")).getParentFile / "test/transport/check-process-trees.py"
        val driver = new ProcessBuilder("python3", helper.getAbsolutePath, request.getAbsolutePath)
          .redirectErrorStream(true)
          .redirectOutput(log)
          .start()
        try
          assert(driver.waitFor(90, TimeUnit.SECONDS), "Process-tree driver exceeded watchdog")
          assert(driver.exitValue() == 0, IO.read(log))
        finally
          if driver.isAlive then
            driver.toHandle.descendants().iterator().asScala.foreach(_.destroyForcibly()); driver.destroyForcibly();
            driver.waitFor(2, TimeUnit.SECONDS)
      IO.write(evidence / "observations.json", ujson.write(ujson.Arr.from(observations), indent = 2))
      IO.write(
        evidence / "provenance.json",
        ujson.write(
          ujson.Obj(
            "backend" -> backend,
            "os" -> sys.props("os.name"),
            "jdk" -> sys.props("java.runtime.version"),
            "scala" -> scalaVersion.value,
            "artifacts" -> ujson.Arr.from(
              (artifacts.filter(f =>
                command.contains(f.getAbsolutePath) ||
                  Set("worker-lifeline.mjs", "progress-deadline.mjs")(f.getName) || f.getName.endsWith(".wasm")
              ) ++ (Compile / sources).value).map(f =>
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

end ForkContainmentChecks
