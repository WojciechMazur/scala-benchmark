package scala.scalanative.jmh

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import org.openjdk.jmh.annotations.Mode

trait ProcessPlatform {
  def outputContext[A](options: Options)(body: => A): A = body

  def prepareWorker(): Unit = ()
  def processId: Long = java.lang.ProcessHandle.current().pid()
  def attachFork(process: Process): Long = 0L
  def releaseFork(process: Process, token: Long): Unit = ()
  def restoreCallerInterrupt(): Unit = Thread.currentThread().interrupt()
  def isCallerInterrupted: Boolean = Thread.currentThread().isInterrupted
  def ensureMeasurementHealthy(): Unit = WorkerContainment.check()
  def requestGC(): Unit = System.gc()
  def processorCount: Int = Runtime.getRuntime().availableProcessors()
  def validate(config: Configuration): Unit = ()
  def metadataSection: String = "native"
  def runtimeVersion: String = "native"
  def inventory: Vector[String] = {
    Option(getClass.getResourceAsStream("/native-jmh/benchmarks.list"))
      .map { stream =>
        val source = scala.io.Source.fromInputStream(stream, "UTF-8")
        try source.getLines().filter(_.nonEmpty).toVector
        finally source.close()
      }
      .getOrElse(Vector.empty)
  }
  def buildMetadata: Vector[(String, String)] = {
    val properties = new java.util.Properties
    val stream = getClass.getResourceAsStream("/native-jmh/build.properties")
    if (stream != null) try properties.load(stream)
    finally stream.close()
    import scala.jdk.CollectionConverters.*
    properties.stringPropertyNames().asScala.toVector.sorted.map(k => k -> properties.getProperty(k))
  }
  def validateOutput(path: String): Unit = {
    val file = Paths.get(path)
    require(
      !Files.exists(file) || Files.isRegularFile(file) || path == "/dev/null" || path.equalsIgnoreCase("NUL"),
      "Benchmark output destination must be a regular file: " + path
    )
    val destination = if (Files.exists(file)) file else file.toAbsolutePath.getParent
    require(Files.isWritable(destination), "Benchmark output destination is not writable: " + path)
  }
  def write(path: String, text: String): Unit = {
    validateOutput(path)
    Files.write(Paths.get(path), text.getBytes(StandardCharsets.UTF_8)); ()
  }
  def consoleStream(destination: java.io.PrintStream, channel: Int, budget: Long): java.io.PrintStream =
    new java.io.PrintStream(new BoundedConsole(destination, budget), true, "UTF-8")
  def forked(
      suite: String,
      descriptor: Descriptor,
      mode: Mode,
      config: Configuration,
      parameters: Map[String, String],
      warmups: Vector[WarmupRequest] = Vector.empty,
      failOnError: Boolean = true,
      observer: RunObserver = RunObserver.Disabled
  ): Vector[IterationResult] = Main.forked(suite, descriptor, mode, config, parameters, warmups, failOnError, observer)
}
