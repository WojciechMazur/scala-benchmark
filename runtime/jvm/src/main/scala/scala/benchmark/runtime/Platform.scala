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

import java.lang.management.ManagementFactory
import java.nio.file.Paths

import scala.jdk.CollectionConverters.*

import org.openjdk.jmh.annotations.{CompilerControl, Fork}

class ControlFields:
  val notifyControl = new org.openjdk.jmh.infra.Control
  @volatile var done: Boolean = false
  @volatile var failure: Throwable = null
  val ready = new java.util.concurrent.CountDownLatch(1)
  def announceReady(): Unit = ready.countDown()
  val started = new java.util.concurrent.atomic.AtomicInteger
  val finished = new java.util.concurrent.atomic.AtomicInteger

object Platform extends ProcessPlatform:
  def haltWorker(): Unit = Runtime.getRuntime.halt(1)
  def identity: scala.benchmark.Platform = scala.benchmark.Platform.JVM

  /** Tests may also name a hand-written suite object. */
  def loadSuite(name: String): Suite = Registry.suite(name).getOrElse {
    Class
      .forName(name, true, Thread.currentThread().getContextClassLoader)
      .getField("MODULE$")
      .get(null)
      .asInstanceOf[Suite]
  }

  def description: String = "JVM (" + System.getProperty("java.vm.name", "unknown") + ")"
  override def metadataSection: String = "jvmBackend"
  override def runtimeVersion: String = System.getProperty("java.vm.version", "unknown")

  override def defaultBuildInfo: Seq[(String, String)] = Seq(
    "javaVersion" -> System.getProperty("java.version", "unknown"),
    "javaVendor" -> System.getProperty("java.vendor", "unknown")
  )

  /** Fork options of the current run; workers are started from the coordinating run only. */
  @volatile private var options = Options()

  override def outputContext[A](options: Options)(body: => A): A =
    this.options = options
    body

  private def java: String = options.jvm.getOrElse(
    ProcessHandle.current().info().command().orElse(Paths.get(System.getProperty("java.home"), "bin", "java").toString)
  )

  /** Options of this JVM, without the debugger agent: a fork must not try to bind the same port. */
  private def inheritedArguments: Vector[String] =
    ManagementFactory.getRuntimeMXBean.getInputArguments.asScala.toVector
      .filterNot(argument => argument.startsWith("-agentlib:jdwp") || argument.startsWith("-Xrunjdwp"))

  /** The entry point to start again in the fork, which installs the same suites before running the worker. */
  private def entryPoint: Vector[String] =
    val command = System.getProperty("sun.java.command", "").trim
    require(command.nonEmpty, "Cannot determine the benchmark main class of this JVM to start forks; use -f 0")
    val main = command.takeWhile(!_.isWhitespace)
    if main.endsWith(".jar") then Vector("-jar", main)
    else Vector("-cp", System.getProperty("java.class.path"), main)

  /** JMH's compiler hints for its Blackhole, using HotSpot compiler blackholes when this JVM supports them. */
  private lazy val blackholeHints: Vector[String] =
    val base = Vector(
      "-XX:+UnlockDiagnosticVMOptions",
      "-XX:CompileCommand=quiet",
      "-XX:CompileCommand=dontinline,org/openjdk/jmh/infra/Blackhole.consumeCPU",
      "-XX:CompileCommand=dontinline,org/openjdk/jmh/infra/Blackhole.consumeFull",
      "-XX:CompileCommand=inline,org/openjdk/jmh/infra/Blackhole.consume"
    )
    val compiler = Vector(
      "-XX:+UnlockExperimentalVMOptions",
      "-XX:CompileCommand=blackhole,org/openjdk/jmh/infra/Blackhole.consumeCompiler",
      "-DcompilerBlackholesEnabled=true"
    )
    if supports(compiler) then base ++ compiler else base

  private def supports(arguments: Vector[String]): Boolean =
    try
      val process = new ProcessBuilder((java +: arguments :+ "-version").asJava)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .start()
      process.waitFor() == 0
    catch case _: _root_.java.io.IOException => false

  private def benchmarkClass(descriptor: Descriptor): Option[Class[?]] =
    val name = descriptor.name.substring(0, math.max(0, descriptor.name.lastIndexOf('.')))
    try Some(Class.forName(name, false, Thread.currentThread().getContextClassLoader))
    catch case _: ClassNotFoundException => None

  private def hierarchy(cls: Class[?]): Vector[Class[?]] =
    Iterator.iterate[Class[?]](cls)(_.getSuperclass).takeWhile(_ != null).toVector

  /** `@CompilerControl` on the benchmark class and its methods, as HotSpot compile commands. */
  private def compilerControl(cls: Class[?]): Vector[String] =
    def command(target: String, control: CompilerControl): Option[String] =
      Option(control.value().command()).filter(_.nonEmpty).map(c => s"-XX:CompileCommand=$c,$target")
    hierarchy(cls).flatMap { owner =>
      val internal = owner.getName.replace('.', '/')
      Option(owner.getAnnotation(classOf[CompilerControl])).flatMap(command(internal + ".*", _)).toVector ++
        owner.getDeclaredMethods.toVector.flatMap { method =>
          Option(method.getAnnotation(classOf[CompilerControl])).flatMap(command(internal + "." + method.getName, _))
        }
    }.distinct

  /** `@Fork(jvmArgs, jvmArgsAppend, jvmArgsPrepend)`, the method annotation taking precedence over the class. */
  private def forkAnnotation(cls: Class[?], descriptor: Descriptor): Option[Fork] =
    val method = descriptor.name.substring(descriptor.name.lastIndexOf('.') + 1)
    hierarchy(cls)
      .flatMap(_.getDeclaredMethods.toVector.filter(_.getName == method))
      .flatMap(m => Option(m.getAnnotation(classOf[Fork])))
      .headOption
      .orElse(hierarchy(cls).flatMap(c => Option(c.getAnnotation(classOf[Fork]))).headOption)

  private val blank = "blank_blank_blank_2014"

  private def declared(values: Array[String]): Option[Vector[String]] =
    Option(values.toVector.filterNot(_ == blank)).filter(_.nonEmpty)

  def workerCommand(suite: String, descriptor: Descriptor): Seq[String] =
    val cls = benchmarkClass(descriptor)
    val fork = cls.flatMap(forkAnnotation(_, descriptor))
    val prepend =
      Option(options.jvmArgsPrepend).filter(_.nonEmpty).orElse(fork.flatMap(f => declared(f.jvmArgsPrepend())))
    val replace = options.jvmArgs.orElse(fork.flatMap(f => declared(f.jvmArgs()))).getOrElse(inheritedArguments)
    val append = Option(options.jvmArgsAppend).filter(_.nonEmpty).orElse(fork.flatMap(f => declared(f.jvmArgsAppend())))
    Vector(java) ++ blackholeHints ++ cls.toVector.flatMap(compilerControl) ++ prepend.toVector.flatten ++ replace ++
      append.toVector.flatten ++ entryPoint

  override def jvmInfo: Option[JvmInfo] = Some(
    JvmInfo(
      java,
      options.jvmArgs.getOrElse(inheritedArguments),
      System.getProperty("java.version", "unknown"),
      System.getProperty("java.vm.name", "unknown"),
      System.getProperty("java.vm.version", "unknown")
    )
  )

end Platform
