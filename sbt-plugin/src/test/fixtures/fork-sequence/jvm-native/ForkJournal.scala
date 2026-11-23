package microbench.examples

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}

object ForkJournal:

  def count(path: String): Int =
    if Files.exists(Paths.get(path)) then new String(Files.readAllBytes(Paths.get(path)), UTF_8).toInt else 0

  def next(path: String): Int =
    val value = count(path) + 1
    Files.write(Paths.get(path), value.toString.getBytes(UTF_8))
    value

  def flood(): Unit =
    val chunk = "x" * 65536; for _ <- 0 until 32 do System.out.print(chunk); System.out.flush()

  def crash(): Unit = System.exit(23)
