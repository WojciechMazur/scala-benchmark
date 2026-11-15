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

/** Only used by deterministic harness tests. JVM benchmarks use upstream JMH. */
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

  def executable: String = throw new UnsupportedOperationException(
    "Use upstream JMH on JVM"
  )

  def description: String = "JVM harness tests"
