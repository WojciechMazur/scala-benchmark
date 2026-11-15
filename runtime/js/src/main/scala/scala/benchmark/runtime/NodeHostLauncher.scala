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

import scala.scalajs.js

/** Starts the Node.js host for a benchmark module that was run by plain `node`, such as by `scala-cli run --js`.
  *
  * Build tools that know about the host (the sbt plugin) start the module through it directly. Otherwise the module
  * writes the embedded host to a temporary directory and runs itself again under it, with the same arguments.
  */
private[runtime] object NodeHostLauncher:

  def hostPresent: Boolean = js.typeOf(js.Dynamic.global.__scalaBenchmarkHost) != "undefined"

  private def process: js.Dynamic = js.Dynamic.global.process

  private def builtin(name: String): js.Dynamic =
    if js.typeOf(process.getBuiltinModule) == "function" then process.getBuiltinModule(name)
    else js.Dynamic.global.require(name)

  /** Runs the module under the host and exits with its status; never returns. */
  def relaunch(): Nothing =
    if js.typeOf(process) == "undefined" then
      throw new IllegalStateException("Scala.js benchmarks run on Node.js")
    val fs = builtin("node:fs")
    val os = builtin("node:os")
    val path = builtin("node:path")
    val childProcess = builtin("node:child_process")
    val argv = process.argv.asInstanceOf[js.Array[String]]
    require(argv.length >= 2, "Cannot determine the benchmark module that Node.js is running")
    val module = path.resolve(argv(1)).asInstanceOf[String]
    val arguments = argv.drop(2).toVector
    val directory = fs.mkdtempSync(path.join(os.tmpdir(), "scala-benchmark-host-")).asInstanceOf[String]
    val status =
      try
        NodeHostSources.modules.foreach((name, text) => fs.writeFileSync(path.join(directory, name), text, "utf8"))
        val execArgv = process.execArgv.asInstanceOf[js.Array[String]].toVector
        val collects = arguments.sliding(2).contains(Vector("-gc", "true"))
        val exposeGC = if collects && !execArgv.contains("--expose-gc") then Vector("--expose-gc") else Vector.empty
        val host = path.join(directory, "node-host.mjs").asInstanceOf[String]
        val command = js.Array((execArgv ++ exposeGC ++ Vector(host, module) ++ arguments)*)
        val result = childProcess.spawnSync(process.execPath, command, js.Dynamic.literal(stdio = "inherit"))
        if !js.isUndefined(result.error) && result.error != null then
          throw js.JavaScriptException(result.error)
        if result.status == null then 1 else result.status.asInstanceOf[Int]
      finally fs.rmSync(directory, js.Dynamic.literal(recursive = true, force = true))
    process.exit(status)
    throw new IllegalStateException("Node.js did not exit")

end NodeHostLauncher
