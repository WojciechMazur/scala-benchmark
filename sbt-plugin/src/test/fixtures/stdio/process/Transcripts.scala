package microbench.runtime.stdio

object Transcripts:

  def write(name: String, text: String): Unit =
    java.nio.file.Files.write(java.nio.file.Paths.get("stdio-" + name + ".txt"), text.getBytes("UTF-8")); ()
