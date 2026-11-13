package scala

/** Scala-facing aliases retain the original JMH symbol identities. */
package object benchmark {
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
  export org.openjdk.jmh.runner.{RunnerException, NoBenchmarksException}
  export org.openjdk.jmh.runner.format.{OutputFormat, OutputFormatFactory}
  export org.openjdk.jmh.runner.options.{VerboseMode, WarmupMode}
  export org.openjdk.jmh.results.format.{ResultFormat, ResultFormatFactory, ResultFormatType}
  export org.openjdk.jmh.runner.options.{Options, OptionsBuilder, ChainedOptionsBuilder, CommandLineOptions, TimeValue}
  export org.openjdk.jmh.results.{
    RunResult,
    BenchmarkResult,
    IterationResult,
    Aggregator,
    ThroughputResult,
    AverageTimeResult,
    SampleTimeResult,
    SingleShotResult,
    ScalarResult,
    ScalarDerivativeResult,
    TextResult,
    RawResults,
    BenchmarkTaskResult,
    IterationResultMetaData,
    BenchmarkResultMetaData
  }
  export org.openjdk.jmh.util.{
    Statistics,
    AbstractStatistics,
    ListStatistics,
    MultisetStatistics,
    SingletonStatistics,
    SampleBuffer
  }
  export org.openjdk.jmh.infra.{ThreadParams, BenchmarkParams, IterationParams, Control}
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
}

package benchmark {
  object Mode {
    export org.openjdk.jmh.annotations.Mode.{
      Throughput,
      AverageTime,
      SampleTime,
      SingleShotTime,
      All,
      values,
      valueOf,
      deepValueOf,
      getKnown
    }
  }
  object Scope { export org.openjdk.jmh.annotations.Scope.{Benchmark, Group, Thread, values, valueOf} }
  object Level { export org.openjdk.jmh.annotations.Level.{Trial, Iteration, Invocation, values, valueOf} }
  object CompilerControl {
    type Mode = org.openjdk.jmh.annotations.CompilerControl.Mode
    object Mode {
      export org.openjdk.jmh.annotations.CompilerControl.Mode.{
        BREAK,
        PRINT,
        EXCLUDE,
        INLINE,
        DONT_INLINE,
        COMPILE_ONLY,
        values,
        valueOf
      }
    }
  }
  object AuxCounters {
    type Type = org.openjdk.jmh.annotations.AuxCounters.Type
    object Type { export org.openjdk.jmh.annotations.AuxCounters.Type.{OPERATIONS, EVENTS, values, valueOf} }
  }
  object Blackhole { export org.openjdk.jmh.infra.Blackhole.consumeCPU }
  object Threads { export org.openjdk.jmh.annotations.Threads.MAX }
  object Warmup { export org.openjdk.jmh.annotations.Warmup.{BLANK_ITERATIONS, BLANK_TIME, BLANK_BATCHSIZE} }
  object Measurement { export org.openjdk.jmh.annotations.Measurement.{BLANK_ITERATIONS, BLANK_TIME, BLANK_BATCHSIZE} }
  object Param { export org.openjdk.jmh.annotations.Param.BLANK_ARGS }
  object Fork { export org.openjdk.jmh.annotations.Fork.{BLANK_ARGS, BLANK_FORKS} }
}
