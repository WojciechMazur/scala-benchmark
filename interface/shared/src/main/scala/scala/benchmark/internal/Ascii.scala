/*
 * Copyright (c) 2026, Wojciech Mazur.
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

/** Locale-free protocol names also work on JS/Wasm, which do not provide Locale. */
object Ascii:
  def lower(value: String): String = value.map(c => if c >= 'A' && c <= 'Z' then (c + 32).toChar else c)
  def upper(value: String): String = value.map(c => if c >= 'a' && c <= 'z' then (c - 32).toChar else c)

  def formEncode(value: String): String =
    val hex = "0123456789ABCDEF"
    val result = new java.lang.StringBuilder
    value.getBytes("UTF-8").foreach { byte =>
      val c = byte & 255
      if (c >= 'a' && c <= 'z') ||
        (c >= 'A' && c <= 'Z') ||
        (c >= '0' && c <= '9') || "-_.*".contains(c.toChar)
      then
        result.append(c.toChar)
      else if c == ' ' then result.append('+')
      else
        result.append('%'); result.append(hex.charAt(c >>> 4)); result.append(hex.charAt(c & 15))
    }
    result.toString
