package scala.benchmark.runtime.outputformat

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native @JSImport("node:fs", JSImport.Namespace)
object FileSystem extends js.Object:
  def mkdtempSync(prefix: String): String = js.native
  def writeFileSync(path: String, text: String): Unit = js.native
  def appendFileSync(path: String, text: String): Unit = js.native
  def readFileSync(path: String, encoding: String): String = js.native

@js.native @JSImport("node:os", JSImport.Namespace)
object OS extends js.Object:
  def tmpdir(): String = js.native

object Journal:
  def worker(): Unit = ()
  def crash(): Unit = js.Dynamic.global.process.exit(23)

  def create(prefix: String): String =
    val path = FileSystem.mkdtempSync(OS.tmpdir() + "/output-format-") + "/" + prefix + ".trace"
    FileSystem.writeFileSync(path, "")
    path

  def append(path: String, event: String): Unit = FileSystem.appendFileSync(path, event + "\n")
  def read(path: String): Vector[String] = FileSystem.readFileSync(path, "utf8").split("\n").toVector.filter(_.nonEmpty)

object CancellationChecks:
  def verify(): Int = 0
