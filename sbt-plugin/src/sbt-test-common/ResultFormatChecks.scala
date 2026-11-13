import sbt.{*, given}
import sbt.Keys.*

object ResultFormatChecks {
  val verifyFactoryFiles = taskKey[Unit]("Check standalone public result-format factories")
  def settings(backend: String): Seq[Def.Setting[?]] = Seq(
    verifyFactoryFiles := Def.uncached {
      val repository = file(sys.props("benchmark.scripted.support")).getParentFile.getParentFile.getParentFile
      val directory = if (backend == "jvm") (LocalRootProject / baseDirectory).value else baseDirectory.value
      val deferred = ujson.read(IO.read(directory / "factory-json-deferred.json"))(0)
      assert(deferred("primaryMetric")("rawData").arr.map(_.arr.map(_.num).toSeq).toSeq == Seq(Seq(10)))
      val disabled = ujson.read(IO.read(directory / "factory-raw-disabled.json"))(0)
      assert(disabled("primaryMetric")("rawData").arr.isEmpty)
      assert(disabled("secondaryMetrics")("count")("rawData").arr.map(_.arr.map(_.num).toSeq).toSeq == Seq(Seq(1)))
      for (mode <- Seq("thrpt", "avgt", "sample", "ss")) {
        val data = ujson.read(IO.read(directory / s"factory-$mode.json"))
        assert(data.arr.size == 1)
        val run = data(0)
        assert(run("benchmark").str == "offline.example.ExternalBenchmark.execute")
        assert(run("mode").str == mode)
        assert(run("jmhVersion").str == "supplied-jmh")
        assert(run("jvm").str == "supplied-host")
        assert(run("jvmArgs").arr.map(_.str).toSeq == Seq("supplied-arg"))
        assert(run("jdkVersion").str == "supplied-jdk")
        assert(run("vmName").str == "supplied-vm" && run("vmVersion").str == "supplied-version")
        assert(run("params")("size").str == "λ,quoted\"value")
        assert(run("threads").num == 1 && run("forks").num == 2)
        assert(run("warmupIterations").num == 1 && run("measurementIterations").num == 2)
        assert(run("warmupTime").str == "2 ms" && run("measurementTime").str == "3 ms")
        assert(run("warmupBatchSize").num == 2 && run("measurementBatchSize").num == 4)
        val metric = run("primaryMetric")
        assert(metric("score").num == 25)
        assert(metric("scorePercentiles").obj.contains("99.999"))
        assert(metric("scorePercentiles").obj.contains("99.9999"))
        if (mode == "sample") {
          assert(metric("rawDataHistogram").arr.map(_.arr.size).toSeq == Seq(2, 2))
          val samples = metric("rawDataHistogram").arr.flatMap(_.arr).flatMap(_.arr)
          assert(samples.map(_(0).num).toSeq == Seq(10, 20, 30, 40))
          assert(samples.forall(_(1).num == 1))
        } else assert(metric("rawData").arr.map(_.arr.map(_.num).toSeq).toSeq == Seq(Seq(10, 20), Seq(30, 40)))
        val count = run("secondaryMetrics")("count")
        assert(count("score").num == 2.5 && count("scoreUnit").str == "#")
        assert(count("rawData").arr.map(_.arr.map(_.num).toSeq).toSeq == Seq(Seq(1, 2), Seq(3, 4)))
        assert(!IO.read(directory / s"factory-$mode.json").contains("clockMinimumDeltaNanos"))
        assert(ujson.read(IO.read(directory / s"factory-$mode.json.empty")).arr.isEmpty)
        for (format <- Seq("text", "csv", "scsv", "latex")) {
          val text = IO.read(directory / s"factory-$mode.$format")
          assert(text.contains("execute") && text.contains("count"))
        }
      }
      val evidence = repository / "target/execution-parity/formatters" / backend
      IO.createDirectory(evidence)
      (directory * "factory-*").get().filter(_.isFile).foreach(file => IO.copyFile(file, evidence / file.getName))
    }
  )
}
