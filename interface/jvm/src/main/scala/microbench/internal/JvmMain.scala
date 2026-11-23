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
package microbench.internal

import org.openjdk.jmh.runner.options.CommandLineOptions

/** Project JVM entry point: platform preflight followed by upstream JMH. */
object JvmMain:

  def main(args: Array[String]): Unit =
    val options = new CommandLineOptions(args*)
    if options.shouldHelp() || options.shouldListResultFormats() || options.shouldListProfilers() then
      org.openjdk.jmh.Main.main(args)
    else
      val runner = new microbench.Runner(options)
      if options.shouldListWithParams() then runner.listWithParams(options)
      else if options.shouldList() then runner.list()
      else runner.run()
