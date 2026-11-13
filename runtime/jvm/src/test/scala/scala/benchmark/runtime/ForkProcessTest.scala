package scala.benchmark.runtime

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import org.junit.Assert.*
import org.junit.Test

class ForkProcessTest:

  private def startProbe(arguments: String*): Process =
    val entries =
      Vector(classOf[ForkProcessTest], classOf[Suite], classOf[scala.runtime.BoxedUnit], classOf[scala.deriving.Mirror])
        .map(_.getProtectionDomain.getCodeSource.getLocation.toURI)
        .map(java.nio.file.Paths.get(_).toString)
        .distinct
    val executable = java.nio.file.Paths.get(
      System.getProperty("java.home"),
      "bin",
      if System.getProperty("os.name").startsWith("Windows") then "java.exe" else "java"
    )
    val command = Vector(
      executable.toString,
      "-classpath",
      entries.mkString(java.io.File.pathSeparator),
      "scala.benchmark.runtime.ForkProcessProbe"
    ) ++ arguments
    import scala.jdk.CollectionConverters.*
    new ProcessBuilder(command.asJava).start()

  @Test(timeout = 10000L) def unkillableProcessIsBoundedAndQuarantinesCoordinator(): Unit =
    val child = startProbe("quarantine")
    try
      assertTrue(child.waitFor(5, TimeUnit.SECONDS))
      val errors = new String(child.getErrorStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      assertEquals(errors, 0, child.exitValue())
    finally
      child.destroyForcibly(); child.waitFor(2, TimeUnit.SECONDS)

  @Test(timeout = 10000L) def interruptionKillsRealChildAndPreservesFlagAndCause(): Unit =
    val child = startProbe()
    val restored = new AtomicBoolean
    val cause = new java.util.concurrent.atomic.AtomicReference[Throwable]
    val cleanupError = new IllegalStateException("injected relay cleanup failure")
    val caller = new Thread(new Runnable:
      def run(): Unit =
        try ForkProcess.managed(child) { child.waitFor(); () }(throw cleanupError)
        catch case error: Throwable => cause.set(error)
        finally restored.set(Thread.currentThread().isInterrupted))
    caller.setDaemon(true)
    try
      caller.start()
      caller.interrupt()
      caller.join(5000)
      assertFalse("Fork coordinator did not terminate", caller.isAlive)
      assertFalse("Interrupted fork left a child alive", child.isAlive)
      assertTrue(cause.get().isInstanceOf[InterruptedException])
      assertTrue(cause.get().getSuppressed.contains(cleanupError))
      assertTrue("Fork interruption flag was lost", restored.get())
    finally
      caller.interrupt()
      child.destroyForcibly()
      child.waitFor(2, TimeUnit.SECONDS)

end ForkProcessTest

object ForkProcessProbe:

  def main(args: Array[String]): Unit = if args.isEmpty then Thread.sleep(60000L)
  else
    var alive = true
    val process = new Process:
      def getOutputStream(): java.io.OutputStream = new java.io.ByteArrayOutputStream
      def getInputStream(): java.io.InputStream = new java.io.ByteArrayInputStream(Array.emptyByteArray)
      def getErrorStream(): java.io.InputStream = getInputStream()
      def waitFor(): Int = throw new AssertionError("Unbounded wait used")
      override def waitFor(timeout: Long, unit: TimeUnit): Boolean =
        Thread.sleep(1); !alive
      def exitValue(): Int = if alive then throw new IllegalThreadStateException else 0
      override def isAlive(): Boolean = alive
      def destroy(): Unit = ()
      override def destroyForcibly(): Process = this
    val original = new IllegalStateException("primary failure")
    val started = System.nanoTime()
    val failure = scala.util.Try(ForkProcess.managed(process)(throw original)(())).failed.get
    require(failure.isInstanceOf[UnfinishedWorkersException])
    require(failure.getCause eq original)
    require(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3))
    require(scala.util.Try(WorkerContainment.check()).failed.get.isInstanceOf[UnfinishedWorkersException])
    alive = false
    WorkerContainment.check()
