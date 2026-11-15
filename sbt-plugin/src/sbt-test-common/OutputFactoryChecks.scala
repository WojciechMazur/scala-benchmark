import sbt.{*, given}
import sbt.Keys.*

object OutputFactoryChecks:
  val verifyOutputFactory = taskKey[Unit]("Compare standard output formatter transcripts with upstream JMH")

  private val headers = Vector(
    "# JMH version:",
    "# VM version:",
    "# VM invoker:",
    "# VM options:",
    "# Warmup:",
    "# Measurement:",
    "# Timeout:",
    "# Threads:",
    "# Benchmark mode:",
    "# Benchmark:",
    "# Parameters:"
  )

  private def lines(text: String): String = text.replace("\r\n", "\n")

  private def header(text: String): String =
    lines(text).linesIterator.filter(line => headers.exists(line.startsWith)).mkString("\n")

  def settings(backend: String): Seq[Def.Setting[?]] = Seq(
    verifyOutputFactory := Def.uncached {
      val repository = FixtureBuild.root
      val directory = baseDirectory.value
      val evidence = repository / "target/execution-parity/output-factory" / backend
      IO.createDirectory(evidence)
      val files = (directory * "output-factory-*.txt").get().filter(_.isFile)
      assert(files.size == 111, s"Expected 111 transcripts, got ${files.size}")
      var compared = 0
      val differences = scala.collection.mutable.ArrayBuffer.empty[String]
      for file <- files do
        val text = IO.read(file)
        val name = file.getName
        if name.contains("-silent-") || name.endsWith("-silent.txt") then
          if name == "output-factory-raw-silent.txt" then assert(text == "XYZtail")
          else if name.startsWith("output-factory-runner-silent-") then
            assert(!text.contains("# Benchmark:") && !text.contains("Iteration   1:"), name)
          else assert(text.isEmpty, name)
        if
          backend != "jvm" && !name.contains("runner-") && !name.contains("listing-") && !name.endsWith("-summary.txt")
        then
          val reference = evidence.getParentFile / "jvm" / name
          assert(reference.isFile, "Run output-factory/jvm before portable formatter checks")
          val expected = IO.read(reference)
          val actual = if name.endsWith("-header.txt") then header(text) else lines(text)
          val oracle = if name.endsWith("-header.txt") then header(expected) else lines(expected)
          if actual != oracle then
            differences += s"$backend/$name differs from upstream:\nEXPECTED:\n$oracle\nACTUAL:\n$actual"
          compared += 1
        IO.copyFile(file, evidence / name)
      assert(differences.isEmpty, differences.mkString("\n"))
      IO.write(
        evidence / "verification.json",
        ujson.write(
          ujson.Obj("backend" -> backend, "transcripts" -> files.size, "oracleComparisons" -> compared),
          indent = 2
        )
      )
    }
  )

end OutputFactoryChecks
