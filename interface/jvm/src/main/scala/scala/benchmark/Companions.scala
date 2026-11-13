package scala.benchmark

/** Java static modules need explicit Scala terms; portable backends export the original Scala companions. */
object TimeValue {
  export org.openjdk.jmh.runner.options.TimeValue.{
    NONE,
    days,
    hours,
    minutes,
    seconds,
    milliseconds,
    microseconds,
    nanoseconds,
    tuToString,
    valueOf,
    fromString
  }
}
object VerboseMode { export org.openjdk.jmh.runner.options.VerboseMode.{SILENT, NORMAL, EXTRA, values, valueOf} }
object WarmupMode { export org.openjdk.jmh.runner.options.WarmupMode.{INDI, BULK, BULK_INDI, values, valueOf} }
object ResultFormatType {
  export org.openjdk.jmh.results.format.ResultFormatType.{TEXT, CSV, SCSV, JSON, LATEX, values, valueOf}
}
object RunResult { export org.openjdk.jmh.results.RunResult.DEFAULT_SORT_COMPARATOR }

object ResultFormatFactory { export org.openjdk.jmh.results.format.ResultFormatFactory.getInstance }

object OutputFormatFactory { export org.openjdk.jmh.runner.format.OutputFormatFactory.createFormatInstance }
