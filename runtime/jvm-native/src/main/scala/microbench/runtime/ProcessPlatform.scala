/*
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  The copyright holders
 * designate this particular file as subject to the "Classpath" exception
 * as provided in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package microbench.runtime

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import org.openjdk.jmh.annotations.Mode

trait ProcessPlatform:
  def outputContext[A](options: Options)(body: => A): A = body

  def prepareWorker(): Unit = ()
  def processId: Long = java.lang.ProcessHandle.current().pid()
  def attachFork(process: Process): Long = 0L

  /** `established` tells whether the worker reported its own process id, i.e. set up its containment. */
  def releaseFork(process: Process, token: Long, established: => Boolean): Unit = ()
  def restoreCallerInterrupt(): Unit = Thread.currentThread().interrupt()
  def isCallerInterrupted: Boolean = Thread.currentThread().isInterrupted
  def ensureMeasurementHealthy(): Unit = WorkerContainment.check()
  def requestGC(): Unit = System.gc()
  def processorCount: Int = Runtime.getRuntime().availableProcessors()
  def validate(config: Configuration): Unit = ()
  def metadataSection: String = "native"
  def runtimeVersion: String = "native"
  def inventory: Vector[String] = Registry.names
  def buildMetadata: Vector[(String, String)] = Registry.buildInfo

  /** What the running program can report about its own build, for build tools that pass no build information. */
  def defaultBuildInfo: Seq[(String, String)] = Nil

  /** Where fork directories are created. */
  def temporaryDirectory: java.nio.file.Path = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"))

  /** The program arguments as the operating system passed them. */
  def commandLine(args: Array[String]): Array[String] = args

  /** The command starting a worker process for one benchmark; worker arguments follow it. */
  def workerCommand(suite: String, descriptor: Descriptor): Seq[String]

  /** JVM identity reported in JMH's result fields; `None` outside the JVM. */
  def jvmInfo: Option[JvmInfo] = None

  /** Build settings that make measurements unrepresentative, reported once per run. */
  def buildWarnings: Seq[String] = Nil

  def validateOutput(path: String): Unit =
    val file = Paths.get(path)
    require(
      !Files.exists(file) || Files.isRegularFile(file) || path == "/dev/null" || path.equalsIgnoreCase("NUL"),
      "Benchmark output destination must be a regular file: " + path
    )
    val destination = if Files.exists(file) then file else file.toAbsolutePath.getParent
    require(Files.isWritable(destination), "Benchmark output destination is not writable: " + path)

  def write(path: String, text: String): Unit =
    validateOutput(path)
    Files.write(Paths.get(path), text.getBytes(StandardCharsets.UTF_8)); ()

  def consoleStream(destination: java.io.PrintStream, channel: Int, budget: Long): java.io.PrintStream =
    new java.io.PrintStream(new BoundedConsole(destination, budget), true, "UTF-8")

  def forked(
      suite: String,
      descriptor: Descriptor,
      mode: Mode,
      config: Configuration,
      parameters: Map[String, String],
      warmups: Vector[WarmupRequest],
      failOnError: Boolean,
      observer: RunObserver
  ): Vector[IterationResult] = Main.forked(suite, descriptor, mode, config, parameters, warmups, failOnError, observer)

end ProcessPlatform
