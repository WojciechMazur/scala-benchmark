package scala.benchmark.runtime

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.junit.Assert.*
import org.junit.Test

class ForkOutputTest {
  @Test def chunksPreserveLongLinesAndUnicode(): Unit = {
    val text = ("x" * 8191 + "\ud83d\ude00\u00e9") * 5
    val output = new ByteArrayOutputStream
    val relay = new ForkOutput(new ByteArrayInputStream(text.getBytes("UTF-8")), new PrintStream(output, true, "UTF-8"))
    relay.await(2000000000L)
    assertEquals(text, output.toString("UTF-8"))
  }
  @Test def rawTailPreservesBinaryAndCallerStreamOwnership(): Unit = {
    val file = java.nio.file.Files.createTempFile("benchmark-user-output", ".bin")
    val alive = new java.util.concurrent.atomic.AtomicBoolean(true)
    val bytes = Array.tabulate[Byte](20000)(_.toByte)
    val captured = new ByteArrayOutputStream
    val destination = new PrintStream(captured)
    val relay =
      new ForkOutput(new java.io.FileInputStream(file.toFile), destination, raw = true, keepReading = () => alive.get())
    try {
      java.nio.file.Files.write(file, bytes)
      alive.set(false)
      relay.await(5000000000L)
      assertArrayEquals(bytes, captured.toByteArray)
      destination.write(42)
      assertEquals(bytes.length + 1, captured.size())
    } finally { alive.set(false); java.nio.file.Files.deleteIfExists(file) }
  }
  @Test(timeout = 10000L) def blockedSinkIsBoundedAndQuarantinesRelay(): Unit = {
    import scala.jdk.CollectionConverters.*
    val paths =
      Vector(classOf[ForkOutputTest], classOf[Suite], classOf[scala.runtime.BoxedUnit], classOf[scala.deriving.Mirror])
        .map(_.getProtectionDomain.getCodeSource.getLocation.toURI)
        .map(java.nio.file.Paths.get(_).toString)
        .distinct
    val executable = java.nio.file.Paths.get(
      System.getProperty("java.home"),
      "bin",
      if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
    )
    val process = new ProcessBuilder(
      Vector(
        executable.toString,
        "-classpath",
        paths.mkString(java.io.File.pathSeparator),
        "scala.benchmark.runtime.ForkOutputProbe"
      ).asJava
    ).start()
    try {
      assertTrue("Output probe exceeded watchdog", process.waitFor(5, TimeUnit.SECONDS))
      val errors = new String(process.getErrorStream.readAllBytes(), "UTF-8")
      assertEquals(errors, 0, process.exitValue())
    } finally { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS) }
  }
}

object ForkOutputProbe {
  def main(args: Array[String]): Unit = {
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val destination = new PrintStream(new java.io.OutputStream {
      def write(value: Int): Unit = { entered.countDown(); release.await() }
    })
    val relay = new ForkOutput(new ByteArrayInputStream(new Array[Byte](32768)), destination)
    require(entered.await(2, TimeUnit.SECONDS))
    val started = System.nanoTime()
    try {
      val failure = scala.util.Try(relay.await(10000000L)).failed.get
      require(failure.isInstanceOf[UnfinishedWorkersException])
      require(failure.getCause.isInstanceOf[ForkOutputException])
      require(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1))
      require(scala.util.Try(WorkerContainment.check()).isFailure)
      require(scala.util.Try(ForkOutputFailure.check()).isFailure)
      val exited = new Process {
        def getOutputStream(): java.io.OutputStream = new ByteArrayOutputStream
        def getInputStream(): java.io.InputStream = new ByteArrayInputStream(Array.emptyByteArray)
        def getErrorStream(): java.io.InputStream = getInputStream()
        def waitFor(): Int = 0
        def exitValue(): Int = 0
        def destroy(): Unit = ()
        override def isAlive(): Boolean = false
      }
      val primary = new java.io.IOException("primary transport failure")
      val combined = scala.util.Try(ForkProcess.managed(exited) { throw primary } { relay.await(10000000L) }).failed.get
      require(combined.isInstanceOf[UnfinishedWorkersException])
      require(combined.getCause eq primary)
      require(combined.getSuppressed.exists(_.isInstanceOf[UnfinishedWorkersException]))
    } finally release.countDown()
    relay.await(2000000000L)
    WorkerContainment.check()
  }
}
