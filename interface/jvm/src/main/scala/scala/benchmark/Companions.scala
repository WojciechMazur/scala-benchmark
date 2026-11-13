package scala.benchmark

/** Java static modules need explicit Scala terms; portable backends export the original Scala companions. */
object TimeValue:

  export org.openjdk.jmh.runner.options.TimeValue.{
    days,
    fromString,
    hours,
    microseconds,
    milliseconds,
    minutes,
    nanoseconds,
    seconds,
    tuToString,
    valueOf,
    NONE
  }

object VerboseMode:
  export org.openjdk.jmh.runner.options.VerboseMode.{valueOf, values, EXTRA, NORMAL, SILENT}

object WarmupMode:
  export org.openjdk.jmh.runner.options.WarmupMode.{valueOf, values, BULK, BULK_INDI, INDI}

object ResultFormatType:
  export org.openjdk.jmh.results.format.ResultFormatType.{valueOf, values, CSV, JSON, LATEX, SCSV, TEXT}

object RunResult:
  export org.openjdk.jmh.results.RunResult.DEFAULT_SORT_COMPARATOR

object ResultFormatFactory:
  export org.openjdk.jmh.results.format.ResultFormatFactory.getInstance

object OutputFormatFactory:
  export org.openjdk.jmh.runner.format.OutputFormatFactory.createFormatInstance
