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
package scala.benchmark.runtime

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import org.openjdk.jmh.annotations.Mode

trait ProcessPlatform:
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
  def inventory: Vector[String] = Registry.names
  def buildMetadata: Vector[(String, String)] = Registry.buildInfo

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
      warmups: Vector[WarmupRequest] = Vector.empty,
      failOnError: Boolean = true,
      observer: RunObserver = RunObserver.Disabled
  ): Vector[IterationResult] = Main.forked(suite, descriptor, mode, config, parameters, warmups, failOnError, observer)

end ProcessPlatform
