package org.openjdk.jmh.results.format

enum ResultFormatType extends java.lang.Enum[ResultFormatType]:
  case TEXT, CSV, SCSV, JSON, LATEX, HTML

object ResultFormatType:

  @scala.annotation.targetName("javaValues")
  def values(): Array[ResultFormatType] = Array(TEXT, CSV, SCSV, JSON, LATEX, HTML)
