package microbench.runtime.stdio

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native @JSImport("node:fs", JSImport.Namespace)
object FileSystem extends js.Object:
  def writeFileSync(path: String, text: String): Unit = js.native

object Transcripts:
  def write(name: String, text: String): Unit = FileSystem.writeFileSync("stdio-" + name + ".txt", text)
