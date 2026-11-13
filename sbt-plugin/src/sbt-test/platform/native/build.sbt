import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport.*
lazy val benchmarks = FixtureBuild
  .matrix("native")
  .settings(
    Compile / nativeConfig ~= (_.withLTO(scala.scalanative.build.LTO(sys.env.getOrElse("SCALANATIVE_LTO", "none"))))
  )
lazy val root = project
  .in(file("."))
  .settings(
    FixtureBuild.settings(benchmarks.native(sys.props("benchmark.scala.version")), "native")
  )

val verifyExecutablePath = taskKey[Unit]("Run a fork from a Unicode path containing spaces")
verifyExecutablePath := Def.uncached {
  val converter = fileConverter.value
  val linked =
    converter.toPath((benchmarks.native(sys.props("benchmark.scala.version")) / Compile / nativeLink).value).toFile
  val directory = target.value / "benchmark paths żółw 東京 🚀"
  val executable = directory / linked.getName
  val report = directory / "measurements żółw.json"
  IO.createDirectory(directory)
  IO.copyFile(linked, executable, preserveExecutable = true)
  val exit = scala.sys.process
    .Process(
      Seq(
        executable.getAbsolutePath,
        "LifecycleBenchmark.sum",
        "-p",
        "size=16",
        "-wi",
        "0",
        "-i",
        "1",
        "-r",
        "10ms",
        "-f",
        "1",
        "-rf",
        "json",
        "-rff",
        report.getAbsolutePath
      ),
      directory,
      "TMP" -> directory.getAbsolutePath,
      "TEMP" -> directory.getAbsolutePath,
      "TMPDIR" -> directory.getAbsolutePath
    )
    .!
  assert(exit == 0, s"Benchmark at $executable exited with $exit")
  val results = ujson.read(IO.read(report)).arr
  assert(results.nonEmpty)
  results.foreach { result =>
    assert(result("forks").num == 1)
    assert(result("primaryMetric")("score").num.isFinite)
    assert(result("params")("size").str == "16")
  }
}
