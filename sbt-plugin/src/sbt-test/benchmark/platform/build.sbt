import pl.project13.scala.sbt.JmhPlugin.JmhKeys.Jmh
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport.nativeLink

val verifyExecutablePath = taskKey[Unit]("Run a fork from a Unicode path containing spaces")

/** The linked executable must keep working after it is copied elsewhere. */
val executablePathCheck = Seq(
  verifyExecutablePath := Def.uncached {
    val linked = fileConverter.value.toPath((Jmh / nativeLink).value).toFile
    val directory = target.value / "benchmark paths żółw 東京 🚀"
    val executable = directory / linked.getName
    val report = directory / "measurements żółw.json"
    IO.createDirectory(directory)
    IO.copyFile(linked, executable, preserveExecutable = true)
    val arguments =
      Seq("LifecycleBenchmark.sum", "-p", "size=16", "-wi", "0", "-i", "1", "-r", "10ms", "-f", "1", "-rf", "json")
    val temporary = Seq("TMP", "TEMP", "TMPDIR").map(_ -> directory.getAbsolutePath)
    val command = (executable.getAbsolutePath +: arguments) ++ Seq("-rff", report.getAbsolutePath)
    val exit = scala.sys.process.Process(command, directory, temporary*).!
    assert(exit == 0, s"Benchmark at $executable exited with $exit")
    val results = ujson.read(IO.read(report)).arr
    assert(results.nonEmpty)
    results.foreach { result =>
      assert(result("forks").num == 1)
      assert(result("primaryMetric")("score").num.isFinite)
      assert(result("params")("size").str == "16")
    }
  }
)

lazy val benchmarks = FixtureBuild.matrix(FixtureBuild.allPlatforms*) { platform =>
  FixtureBuild.settings(platform) ++ (if (platform == "native") executablePathCheck else Nil)
}

lazy val root = project.in(file(".")).settings(FixtureBuild.commands)
