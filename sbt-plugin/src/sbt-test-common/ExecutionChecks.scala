import sbt.{*, given}
import sbt.Keys.*

object ExecutionChecks:
  val verifyExecution = taskKey[Unit]("Validate execution-semantic fixture results")

  def settings(backend: String): Seq[Setting[?]] = Seq(
    verifyExecution := Def.uncached {
      val directory = baseDirectory.value
      val repository = FixtureBuild.root
      val evidence = repository / "target/execution-parity/platforms" / backend
      IO.createDirectory(evidence)
      val converter = fileConverter.value
      val dependencies = (Compile / dependencyClasspath).value
        .map(entry => converter.toPath(entry.data).toFile)
        .filter(path => path.isFile && (path.getName.startsWith("microbench-") || path.getName.startsWith("jmh-")))
      val plugins = (Compile / scalacOptions).value
        .filter(_.startsWith("-Xplugin:"))
        .flatMap(_.stripPrefix("-Xplugin:").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
        .map(file)
        .filter(_.isFile)
      val fixtureSources = (Compile / sources).value
      def fingerprint(path: File): ujson.Value =
        val hash = FixtureBuild.sha256(path)
        ujson.Obj("name" -> path.getName, "sha256" -> hash)
      IO.write(
        evidence / "provenance.json",
        ujson.write(
          ujson.Obj(
            "backend" -> backend,
            "scala" -> scalaVersion.value,
            "jdk" -> sys.props("java.runtime.version"),
            "os" -> sys.props("os.name"),
            "arch" -> sys.props("os.arch"),
            "artifacts" -> ujson.Arr.from((dependencies ++ plugins ++ fixtureSources).distinct.map(fingerprint))
          ),
          indent = 2
        )
      )
      for
        (file, name) <- Vector(
          "readiness.json" -> "ExecutionReadinessBenchmark.execute",
          "lifecycle.json" -> "ExecutionFailureProbe.execute",
          "shared-lifecycle.json" -> "SharedLifecycleBenchmark.execute"
        ) ++
          (if backend == "jvm" || backend == "native" then
             Vector("shared-group.json" -> "SharedGroupLifecycleBenchmark.identity")
           else Vector.empty)
      do
        val result = ujson.read(IO.read(directory / file)).arr
        assert(result.size == 1, s"$file: expected exactly one successful measurement")
        assert(result.head("benchmark").str.endsWith(name))
        assert(result.head("primaryMetric")("score").num.isFinite)
        assert(result.head("primaryMetric")("rawData").arr.size == 1)
        assert(result.head("primaryMetric")("rawData").arr.head.arr.size ==
          (if file.startsWith("shared-") then 2 else 1))
        IO.copyFile(directory / file, evidence / file)
    }
  )

end ExecutionChecks
