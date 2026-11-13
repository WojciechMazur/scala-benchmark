package scala.scalanative.jmh

import java.io.File
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

import scala.benchmark.internal.InventoryEntry
import scala.jdk.CollectionConverters.*

/** Compiles benchmark sources and then, as the sbt plugin does, a generated source that derives their harnesses. */
object HarnessCompiler:
  val classpath: String =
    val stream = getClass.getResourceAsStream("/harness-test.classpath")
    try new String(stream.readAllBytes(), UTF_8)
    finally stream.close()

  private def scalac(output: Path, sources: Seq[Path]): Boolean =
    val arguments = Array("-classpath", output.toString + File.pathSeparator + classpath, "-d", output.toString)
    !dotty.tools.dotc.Main.process(arguments ++ sources.map(_.toString)).hasErrors

  private def generatedName(cls: String): String = cls.substring(cls.lastIndexOf('.') + 1)

  /** Second compilation stage: one object per benchmark class, holding its harness and inventory. */
  def derive(output: Path, classes: Seq[String]): Boolean =
    val source = output.getParent.resolve("Generated.scala")
    val objects = classes.map { cls =>
      s"""object ${generatedName(cls)} {
         |  val suite: scala.scalanative.jmh.Suite = scala.scalanative.jmh.Harness.derive[$cls]
         |  val inventory: Array[scala.benchmark.internal.InventoryEntry] = scala.benchmark.internal.Inventory.of[$cls]
         |}""".stripMargin
    }
    Files.write(source, objects.mkString("package generated\n", "\n", "\n").getBytes(UTF_8))
    scalac(output, Seq(source))

  private def generated(loader: ClassLoader, cls: String): AnyRef =
    loader.loadClass("generated." + generatedName(cls) + "$").getField("MODULE$").get(null)

  def suite(loader: ClassLoader, cls: String): Suite =
    val module = generated(loader, cls)
    module.getClass.getMethod("suite").invoke(module).asInstanceOf[Suite]

  def inventory(loader: ClassLoader, cls: String): Vector[InventoryEntry] =
    val module = generated(loader, cls)
    module.getClass.getMethod("inventory").invoke(module).asInstanceOf[Array[InventoryEntry]].toVector

  /** Recompiles `sources` into an existing output directory, without deriving harnesses. */
  def recompile(output: Path, sources: Path*): Boolean = scalac(output, sources)

  def compile(source: String, derive: Seq[String] = Seq("bench.Example"))(check: (Boolean, Path) => Unit): Unit =
    val directory = Files.createTempDirectory("benchmark-harness-")
    val output = Files.createDirectory(directory.resolve("classes"))
    val input = directory.resolve("Benchmark.scala")
    Files.write(input, source.getBytes(UTF_8))
    try check(scalac(output, Seq(input)) && (derive.isEmpty || this.derive(output, derive)), output)
    finally
      val files = Files.walk(directory)
      try files.iterator().asScala.toVector.reverse.foreach(Files.delete)
      finally files.close()

  def withLoader[A](output: Path)(body: URLClassLoader => A): A =
    val loader = new URLClassLoader(Array(output.toUri.toURL), getClass.getClassLoader)
    try body(loader)
    finally loader.close()
