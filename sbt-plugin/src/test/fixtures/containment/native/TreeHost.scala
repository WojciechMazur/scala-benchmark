package scala.benchmark.examples

object TreeHost:

  def start(path: String): Unit =
    new ProcessBuilder(
      "sh",
      "-c",
      "sleep 60 & grand=$!; printf '%s %s' \"$$\" \"$grand\" > \"$1\"; wait",
      "benchmark-tree",
      path
    )
      .redirectOutput(ProcessBuilder.Redirect.INHERIT)
      .redirectError(ProcessBuilder.Redirect.INHERIT)
      .start()
    val started = System.nanoTime()
    def ready: Boolean = scala.util
      .Try(
        new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), "UTF-8").trim
          .matches("[0-9]+ [0-9]+")
      )
      .getOrElse(false)
    while !ready && System.nanoTime() - started < 2000000000L do Thread.sleep(1)
    require(ready, "Tree child did not start")

  def crash(): Unit = System.exit(23)
