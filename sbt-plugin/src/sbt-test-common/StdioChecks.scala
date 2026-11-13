import sbt.{*, given}
import sbt.Keys.*
import pl.project13.scala.sbt.JmhPlugin.JmhKeys.*
import scala.jdk.CollectionConverters.*
import java.util.concurrent.TimeUnit
object StdioChecks {
  val verifyStdio = taskKey[Unit]("Check benchmark output channel provenance against JVM JMH")
  def settings(backend: String): Seq[Def.Setting[?]] = Seq(
    verifyStdio := Def.uncached {
      val root = file(sys.props("benchmark.scripted.support")).getParentFile.getParentFile.getParentFile
      val directory = baseDirectory.value
      val evidence = root / "target/execution-parity/stdio" / backend
      IO.createDirectory(evidence)
      val files = (directory * "stdio-*.txt").get().filter(_.isFile)
      assert(files.size == 64, s"Expected 64 channel transcripts, found ${files.size}")
      val differences = scala.collection.mutable.ArrayBuffer.empty[String]
      files.foreach { file =>
        val actual = IO.read(file)
        IO.copyFile(file, evidence / file.getName)
        if (backend != "jvm") {
          val reference = evidence.getParentFile / "jvm" / file.getName
          assert(reference.isFile, "Run stdio/jvm first")
          if (actual != IO.read(reference))
            differences += file.getName + " differs from JVM\nEXPECTED:\n" + IO.read(reference) + "\nACTUAL:\n" + actual
        }
      }
      assert(differences.isEmpty, differences.mkString("\n"))
      val converter = fileConverter.value
      val classpath = (Jmh / fullClasspath).value.map(entry => converter.toPath(entry.data).toString)
      val products = ((Compile / target).value ** "*").get().filter(_.isFile)
      val command =
        if (backend == "jvm")
          Vector(
            java.nio.file.Paths.get(sys.props("java.home"), "bin", "java").toString,
            "-Djmh.ignoreLock=true",
            "-cp",
            classpath.mkString(java.io.File.pathSeparator),
            "scala.benchmark.internal.JvmMain"
          )
        else if (backend == "native")
          Vector(products.find(f => f.canExecute && Set("benchmarks", "benchmarks.exe")(f.getName)).get.getAbsolutePath)
        else {
          val host = products.find(_.getName == "node-host.mjs").get
          val module = products.find(f => f.getName == "main.js" && f.getPath.contains("-opt/")).get
          Vector("node", host.getAbsolutePath, module.getAbsolutePath)
        }
      for (forks <- Seq(0, 1)) {
        val out = evidence / s"cli-$forks-out.bin"
        val err = evidence / s"cli-$forks-err.bin"
        val process = new ProcessBuilder(
          (command ++ Vector(
            "scala.scalanative.jmh.stdio.RawTarget.execute",
            "-f",
            forks.toString,
            "-wi",
            "0",
            "-i",
            "1",
            "-v",
            "SILENT",
            "-o",
            (evidence / s"cli-$forks-framework.txt").getAbsolutePath
          )).asJava
        )
          .directory(directory)
          .redirectOutput(out)
          .redirectError(err)
          .start()
        try {
          assert(process.waitFor(30, TimeUnit.SECONDS), s"$backend CLI output watchdog")
          assert(process.exitValue() == 0, IO.read(err))
          for (
            (file, channel, expected) <- Seq(
              (out, "OUT", Array.tabulate[Byte](256)(_.toByte)),
              (err, "ERR", Array.tabulate[Byte](256)(_.toByte).reverse)
            )
          ) {
            val bytes = IO.readBytes(file)
            val start = ("USER-" + channel + "-BEGIN|").getBytes("UTF-8")
            val end = ("|USER-" + channel + "-END").getBytes("UTF-8")
            val index = bytes.toVector.indexOfSlice(start.toVector)
            assert(index >= 0, s"$backend/$forks missing $channel prefix")
            val captured = bytes.slice(index + start.length, index + start.length + expected.length)
            assert(captured.sameElements(expected), s"$backend/$forks corrupted binary $channel")
            assert(
              bytes
                .slice(index + start.length + expected.length, index + start.length + expected.length + end.length)
                .sameElements(end)
            )
          }
        } finally { if (process.isAlive) { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS) } }
      }

      IO.write(
        evidence / "verification.json",
        ujson.write(
          ujson.Obj(
            "backend" -> backend,
            "files" -> files.size,
            "comparisons" -> (if (backend == "jvm") 0 else files.size),
            "standaloneBinaryRuns" -> 2,
            "callerSystemAliasRuns" -> 2
          ),
          indent = 2
        )
      )
    }
  )
}
