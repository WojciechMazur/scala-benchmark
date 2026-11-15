/*
 * Copyright (c) 2026, Scala Benchmark contributors.
 *
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
package scala.benchmark.internal

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

/** An opaque host call makes consumption observable outside linked Scala code. Its cost is part of a benchmark that
  * consumes a value, on JS and Wasm alike.
  */
object BlackholeSink:
  def consume(value: Long): Unit = Sink.consume(value.asInstanceOf[js.Any])
  def consumeReference(value: AnyRef): Unit = Sink.consume(value.asInstanceOf[js.Any])

  @js.native @JSGlobal("__scalaBenchmarkHost")
  private object Sink extends js.Object:
    def consume(value: js.Any): Unit = js.native
