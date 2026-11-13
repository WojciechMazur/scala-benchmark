import sbt.{*, given}
import sbt.Keys.*
import pl.project13.scala.sbt.JmhPlugin.JmhKeys.*
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

object RunnerFailureChecks {
  val verifyRunnerFailures = taskKey[Unit]("Compare embedded and bulk warmup failures with upstream")
  def settings(benchmark: Project, backend: String): Seq[Setting[?]] = Seq(
    verifyRunnerFailures := Def.uncached {
      val directory = (benchmark / baseDirectory).value
      val files = ((benchmark / target).value ** "*").get().filter(_.isFile)
      def artifact(name: String): File = files.find(_.getName == name).getOrElse(sys.error("Missing " + name))
      val converter = fileConverter.value
      val cp = (benchmark / Jmh / fullClasspath).value.map(e => converter.toPath(e.data).toString)
      val command =
        if (backend == "jvm")
          Vector(
            java.nio.file.Paths.get(sys.props("java.home"), "bin", "java").toString,
            "-Djmh.ignoreLock=true",
            "-classpath",
            cp.mkString(java.io.File.pathSeparator),
            "org.openjdk.jmh.Main"
          )
        else if (backend == "native")
          Vector(files.find(f => f.canExecute && Set("benchmarks", "benchmarks.exe")(f.getName)).get.getAbsolutePath)
        else
          Vector(
            "node",
            artifact("node-host.mjs").getAbsolutePath,
            files.find(f => f.getName == "main.js" && f.getPath.contains("-opt/")).get.getAbsolutePath,
            artifact("manifest.json").getAbsolutePath
          )
      val repository = file(sys.props("benchmark.scripted.support")).getParentFile.getParentFile.getParentFile
      val evidence = repository / "target/execution-parity/runner-failures" / backend
      IO.delete(evidence); IO.createDirectory(evidence)
      var observations = Vector.empty[ujson.Value]
      def run(id: String, args: Vector[String]): Unit = {
        val report = evidence / (id + ".json")
        val log = evidence / (id + ".log")
        val child = new ProcessBuilder(
          (command ++ args ++ Vector("-t", "1", "-to", "2s", "-rf", "json", "-rff", report.getAbsolutePath)).asJava
        )
          .directory(directory)
          .redirectErrorStream(true)
          .redirectOutput(log)
          .start()
        var children = Vector.empty[ProcessHandle]
        val start = System.nanoTime()
        try {
          while (child.isAlive && System.nanoTime() - start < TimeUnit.SECONDS.toNanos(20)) {
            children = (children ++ child.toHandle.descendants().iterator().asScala).distinct
            child.waitFor(10, TimeUnit.MILLISECONDS)
          }
          assert(!child.isAlive, id + ": watchdog expired")
          assert(children.forall(!_.isAlive), id + ": surviving child")
          val results =
            if (report.exists() && IO.read(report).trim.nonEmpty) ujson.read(IO.read(report)).arr.toVector
            else Vector.empty
          val text = IO.read(log)
          assert(
            text.contains(if (id.startsWith("bulk")) "warmup failure:" else "embedded failure:"),
            id + ": missing primary cause"
          )
          observations :+= ujson.Obj(
            "case" -> id,
            "exit" -> child.exitValue(),
            "results" -> ujson.Arr.from(
              results.map(row =>
                ujson.Obj(
                  "benchmark" -> row("benchmark").str,
                  "populations" -> ujson.Arr.from(row("primaryMetric")("rawData").arr.map(_.arr.size))
                )
              )
            ),
            "recovery" -> text.contains("EMBEDDED_RECOVERY"),
            "measured" -> text.contains("BULK_MEASUREMENT_OK")
          )
        } finally {
          children.filter(_.isAlive).foreach(_.destroyForcibly())
          if (child.isAlive) child.destroyForcibly()
          child.waitFor(2, TimeUnit.SECONDS)
        }
      }
      for (stage <- Vector("setup", "work", "teardown", "trial"); iteration <- Vector(1, 2); foe <- Vector(false, true))
        run(
          s"embedded-$stage-$iteration-$foe",
          Vector(
            "(EmbeddedFailureBenchmark|ZEmbeddedRecoveryBenchmark)",
            "-p",
            "failure=" + stage,
            "-p",
            "failIteration=" + iteration,
            "-f",
            "0",
            "-wi",
            "0",
            "-i",
            "2",
            "-foe",
            foe.toString
          )
        )
      for (
        mode <- Vector("INDI", "BULK", "BULK_INDI"); stage <- Vector("setup", "work", "teardown");
        forks <- Vector(0, 1); foe <- Vector(false, true)
      )
        run(
          s"bulk-$mode-$stage-$forks-$foe",
          Vector(
            "BulkFailureMeasuredBenchmark",
            "-wmb",
            "Warmup" + stage.capitalize + "FailureBenchmark",
            "-wm",
            mode,
            "-wi",
            "1",
            "-i",
            "1",
            "-f",
            forks.toString,
            "-foe",
            foe.toString
          )
        )
      IO.write(evidence / "observations.json", ujson.write(ujson.Arr.from(observations), indent = 2))
      if (backend != "jvm") {
        val expected = ujson
          .read(IO.read(evidence.getParentFile / "jvm/observations.json"))
          .arr
          .map(row => row("case").str -> row)
          .toMap
        val differences = observations.filter(row => row != expected(row("case").str))
        IO.write(evidence / "differences.json", ujson.write(ujson.Arr.from(differences), indent = 2))
        assert(differences.isEmpty, ujson.write(ujson.Arr.from(differences), indent = 2))
      }
    }
  )
}
