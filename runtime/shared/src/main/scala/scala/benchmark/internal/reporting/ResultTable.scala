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
package scala.benchmark.internal.reporting

object ResultTable:

  def latex(header: Vector[String], rows: Vector[Vector[String]]): String =
    def cell(value: String): String = value.flatMap {
      case '\\' => "\\textbackslash{}"
      case '&'  => "\\&"
      case '%'  => "\\%"
      case '$'  => "\\$"
      case '#'  => "\\#"
      case '_'  => "\\_"
      case '{'  => "\\{"
      case '}'  => "\\}"
      case '~'  => "\\textasciitilde{}"
      case '^'  => "\\textasciicircum{}"
      case '\n' => "\\textbackslash{}n"
      case '\r' => "\\textbackslash{}r"
      case '\t' => "\\textbackslash{}t"
      case c    => c.toString
    }
    val alignment = "llrrrrl" + "l" * (header.size - 7)
    def row(values: Vector[String]): String = values.map(cell).mkString(" & ") + " \\\\"
    (Vector(s"\\begin{tabular}{$alignment}", "\\hline", row(header), "\\hline") ++
      rows.map(row) ++ Vector("\\hline", "\\end{tabular}")).mkString("", "\n", "\n")
