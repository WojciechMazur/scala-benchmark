package scala

/** Scala-facing aliases retain the original JMH symbol identities. */
package object benchmark:

  export org.openjdk.jmh.annotations.{
    Benchmark,
    BenchmarkMode,
    Group,
    GroupThreads,
    OperationsPerInvocation,
    OutputTimeUnit,
    Setup,
    State,
    TearDown,
    Timeout
  }
  export org.openjdk.jmh.infra.{BenchmarkParams, Control, IterationParams, ThreadParams}
  export org.openjdk.jmh.results.{
    Aggregator,
    AverageTimeResult,
    BenchmarkResult,
    BenchmarkResultMetaData,
    BenchmarkTaskResult,
    IterationResult,
    IterationResultMetaData,
    RawResults,
    RunResult,
    SampleTimeResult,
    ScalarDerivativeResult,
    ScalarResult,
    SingleShotResult,
    TextResult,
    ThroughputResult
  }
  export org.openjdk.jmh.results.format.{ResultFormat, ResultFormatFactory, ResultFormatType}
  export org.openjdk.jmh.runner.{NoBenchmarksException, RunnerException}
  export org.openjdk.jmh.runner.format.{OutputFormat, OutputFormatFactory}
  export org.openjdk.jmh.runner.options.{ChainedOptionsBuilder, CommandLineOptions, Options, OptionsBuilder, TimeValue}
  export org.openjdk.jmh.runner.options.{VerboseMode, WarmupMode}
  export org.openjdk.jmh.util.{
    AbstractStatistics,
    ListStatistics,
    MultisetStatistics,
    SampleBuffer,
    SingletonStatistics,
    Statistics
  }

  type Mode = org.openjdk.jmh.annotations.Mode
  type Scope = org.openjdk.jmh.annotations.Scope
  type Level = org.openjdk.jmh.annotations.Level
  type Blackhole = org.openjdk.jmh.infra.Blackhole
  type CompilerControl = org.openjdk.jmh.annotations.CompilerControl
  type AuxCounters = org.openjdk.jmh.annotations.AuxCounters
  type Threads = org.openjdk.jmh.annotations.Threads
  type Warmup = org.openjdk.jmh.annotations.Warmup
  type Measurement = org.openjdk.jmh.annotations.Measurement
  type Param = org.openjdk.jmh.annotations.Param
  type Fork = org.openjdk.jmh.annotations.Fork

end benchmark

package benchmark {

  object Mode:

    export org.openjdk.jmh.annotations.Mode.{
      deepValueOf,
      getKnown,
      valueOf,
      values,
      All,
      AverageTime,
      SampleTime,
      SingleShotTime,
      Throughput
    }

  object Scope:
    export org.openjdk.jmh.annotations.Scope.{valueOf, values, Benchmark, Group, Thread}

  object Level:
    export org.openjdk.jmh.annotations.Level.{valueOf, values, Invocation, Iteration, Trial}

  object CompilerControl:
    type Mode = org.openjdk.jmh.annotations.CompilerControl.Mode

    object Mode:

      export org.openjdk.jmh.annotations.CompilerControl.Mode.{
        valueOf,
        values,
        BREAK,
        COMPILE_ONLY,
        DONT_INLINE,
        EXCLUDE,
        INLINE,
        PRINT
      }

  object AuxCounters:
    type Type = org.openjdk.jmh.annotations.AuxCounters.Type

    object Type:
      export org.openjdk.jmh.annotations.AuxCounters.Type.{valueOf, values, EVENTS, OPERATIONS}

  object Blackhole:
    export org.openjdk.jmh.infra.Blackhole.consumeCPU

  object Threads:
    export org.openjdk.jmh.annotations.Threads.MAX

  object Warmup:
    export org.openjdk.jmh.annotations.Warmup.{BLANK_BATCHSIZE, BLANK_ITERATIONS, BLANK_TIME}

  object Measurement:
    export org.openjdk.jmh.annotations.Measurement.{BLANK_BATCHSIZE, BLANK_ITERATIONS, BLANK_TIME}

  object Param:
    export org.openjdk.jmh.annotations.Param.BLANK_ARGS

  object Fork:
    export org.openjdk.jmh.annotations.Fork.{BLANK_ARGS, BLANK_FORKS}

}
