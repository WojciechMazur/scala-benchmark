package scala.benchmark.runtime.outputfactory

object Transcripts {
  def write(name: String, text: String): Unit = {
    java.nio.file.Files.write(java.nio.file.Paths.get("output-factory-" + name + ".txt"), text.getBytes("UTF-8"))
    ()
  }
}
