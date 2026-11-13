package scala.benchmark.examples

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}

object ForkJournal:

  def count(path: String): Int =
    if Files.exists(Paths.get(path)) then new String(Files.readAllBytes(Paths.get(path)), UTF_8).toInt else 0

  def next(path: String): Int =
    val mode = Option(System.getenv("SCALA_BENCHMARK_TEST_TRANSPORT")).getOrElse("")
    if mode.nonEmpty then
      Runtime.getRuntime.addShutdownHook(new Thread(new Runnable:
        def run(): Unit =
          val directories = Option(new java.io.File(System.getProperty("java.io.tmpdir")).listFiles()).toVector.flatten
          directories.filter(f => f.isDirectory && f.getName.startsWith("scala-benchmark-")).foreach { directory =>
            val request = new java.io.File(directory, "request.properties")
            if request.isFile then
              val properties = new java.util.Properties
              val input = new java.io.FileInputStream(request)
              try properties.load(input)
              finally input.close()
              if properties.getProperty("parameter.ledger") == path then
                val result = directory.toPath.resolve("result.bin")
                if Files.exists(result) then
                  val bytes = Files.readAllBytes(result)
                  mode match
                    case "truncate"   => Files.write(result, bytes.take(bytes.length / 2))
                    case "trailing"   => Files.write(result, bytes ++ Array[Byte](1))
                    case "nonregular" =>
                      Files.delete(result)
                      require(new ProcessBuilder("mkfifo", result.toString).start().waitFor() == 0)
                    case "incomplete" =>
                      val results = scala.benchmark.runtime.Main.readResults(result)
                      scala.benchmark.runtime.Main.writeResults(result, results.take(1))
                    case _ => throw new IllegalArgumentException(mode)
          }))
    val value = count(path) + 1
    Files.write(Paths.get(path), value.toString.getBytes(UTF_8))
    value

  end next

  def flood(): Unit =
    val chunk = "x" * 65536; for _ <- 0 until 32 do System.out.print(chunk); System.out.flush()

  def crash(): Unit = System.exit(23)

end ForkJournal
