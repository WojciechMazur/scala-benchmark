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
