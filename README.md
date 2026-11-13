# scala-benchmark

Scala 3 benchmarking for JVM, Scala Native, and Scala.js under `org.scala-native`.
The build uses sbt 2.0.11, Scala Native 0.5.12, and Scala.js 1.22.0. Requires JDK
17+ and LLVM/Clang for Native execution. It is independent of the Scala Native
checkout.

## Build matrix

The build uses sbt 2's built-in `projectMatrix` for platforms only. Each module has
JVM, Native, and JS projects, for example `runtimeJVM`, `runtimeNative`, and
`runtimeJS`. Scala versions are selected with `++` or compiled together with `+`;
`crossScalaVersions` covers Scala 3.3.3–3.3.9, 3.9.0, and 3.10.0.

`interface` supplies the JMH API and `scala.benchmark` facade; `runtime` supplies
the runner; `examples` shares benchmarks across all three platforms.
`compilerPlugin` is a single JVM project, full-cross-published for each compiler.
`sbtPlugin` builds the sbt-jmh adapters with sbt 2's Scala 3.8.4.
Shared libraries default to Scala 3.3.3 for publication compatibility; examples
and the compiler plugin default to Scala 3.3.9.

```text
+compilerPlugin/compile
++ 3.10.0
runtimeNative/compile
```

`runtime/shared` contains portable runtime sources; `runtime/jvm-native` contains
threaded execution and process support shared by JVM tests and Native. Platform code lives in
`runtime/jvm`, `runtime/native`, and `runtime/js`. Implementation shared with a
compiler plugin should be shared as sources. The compiler plugin's runtime
dependency is only for execution tests.

## Public API

```scala
import scala.benchmark.*

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.SingleShotTime))
class Example {
  @Param(Array("8", "64")) var size: Int = 0
  @Benchmark def run(blackhole: Blackhole): Int = {
    blackhole.consume(size)
    size
  }
}
```

The facade explicitly exports the JMH annotations, enums, `Blackhole`, and
`ThreadParams`, preserving their original symbol identities. Existing
`org.openjdk.jmh` imports also work. JVM interface artifacts use upstream
`jmh-core`; Native and JS artifacts supply the compatibility definitions.
The interface supplies each backend's blackhole: a C barrier on Native and an
observable host call on JS/Wasm. See [optimized measurement integrity](OPTIMIZATION_INTEGRITY.md)
for the local artifact checks and the limits of preserving unused object contents.

Programmatic execution uses the same selection policy:

```scala
val options = new OptionsBuilder()
  .include("example.MyBenchmark.execute")
  .warmupIterations(2).measurementIterations(3).forks(1).build()
val results = new Runner(options).run()
```

A standard output formatter can be supplied explicitly:

```scala
val output = OutputFormatFactory.createFormatInstance(System.out, VerboseMode.NORMAL)
val results = new Runner(options, output).run()
```

`NORMAL` and `EXTRA` provide progress and results; `SILENT` suppresses formatter
text and lifecycle output while forwarding direct raw writes. All modes leave the
supplied stream open. See [standard output-format compatibility](OUTPUT_FACTORY_PARITY.md)
for lifecycle behavior and presentation limits. Benchmark user stdout/stderr
bypasses the formatter, `-o` and SILENT verbosity; forked output reaches the
parent System streams as separate byte channels. See
[benchmark output channels](STDIO_PARITY.md) for embedded console behavior and
transport limits.

Use `scala.benchmark.Runner` on JVM for eligibility preflight before upstream
JMH execution. Direct use of upstream `org.openjdk.jmh.runner.Runner` bypasses
that preflight. Native/JS execution requires the runtime and generated inventory,
which the sbt plugin supplies. Injected `BenchmarkParams`, `IterationParams` and
`Control`, grouped benchmarks, auxiliary counters and portable options/results
are available; see the implementation summary for current limits.

## Sharing benchmarks across platforms

Use `scala.benchmark.Platforms` to declare where a class or benchmark method can
execute. Unannotated benchmarks are eligible on JVM, Native, JS and Wasm.

```scala
import scala.benchmark.*

@Platforms(Array(Platform.JVM, Platform.Native), reason = "Requires concurrent group workers")
class ConcurrentBenchmarks {
  @Benchmark @Group("workers") def read(): Int = 1
  @Benchmark @Group("workers") def write(): Int = 2
}
```

Class and method declarations, including inherited restrictions, intersect;
overrides cannot broaden an inherited restriction. Empty declarations or
intersections are compilation errors. List both `Platform.JS` and
`Platform.Wasm` to allow both. All method bodies must still typecheck on every
backend: this annotation controls execution, not conditional compilation.

Aggregate and regex runs report skips for incompatible benchmarks. A literal
full benchmark name is an exact request and fails before any selected benchmark
executes if it is incompatible. Exclusions suppress those requests. Listing
(`-l` / `-lp`) shows platform restrictions without executing benchmarks. JVM
measurements continue to use upstream JMH after this preflight.

See [the parity roadmap](PARITY_PLAN.md) for remaining JMH compatibility work and
[the implementation summary](WORK_SUMMARY.md) for current coverage.

## Run examples

```text
sbt
> examplesNative/Jmh/run -l
> examplesNative/Jmh/run LifecycleBenchmark.sum
> examplesJVM/Jmh/run .*FacadeBenchmark.execute
> examplesJS/Jmh/run LifecycleBenchmark.sum
```

The examples hash integer keys, scan and transform reusable arrays, and compare
loop-based decimal parsing with an iterator pipeline. Each uses **3 warmup and
5 measurement iterations of 1 second, across 2 fresh forks**, with two input
sizes. Timings are in microseconds per complete batch. Input preparation and
buffer allocation happen in trial setup, outside the measured methods.
One thread is the portable default; JVM and Native can override it with `-t`.
A selected method takes about 32 seconds across both input sizes, plus startup.
For a short smoke run, override the defaults with `-wi 1 -i 3 -w 100ms -r 100ms -f 1`.

The adapters reuse upstream sbt-jmh's `Jmh` configuration, keys, and generation
task. Enable `JmhPlugin` with ScalaNativePlugin or ScalaJSPlugin; the appropriate
adapter activates automatically. JVM execution retains upstream JMH. A small
compiler phase restores original Java enum fields in facade annotations for
older Scala compilers; it does not replace the JVM harness. Native
execution uses generated Scala harnesses and excludes JVM-only JMH dependencies.
On Native, `src/main` and `src/jmh` compile in `Compile`; configure dependencies
and `nativeConfig` there on a dedicated benchmark project.

Scala.js runs full-optimized JS or Wasm modules in Node.js. It supports all four
measurement modes, fixtures, parameters, isolated forks, report exports, and
timeout supervision. The initial backend supports one benchmark thread per
process (`-t 1`). See [the JS runtime guide](runtime/js/README.md) for timing,
blackhole semantics, Node options, and Wasm linker configuration.
See [NATIVE.md](NATIVE.md) for Native measurement semantics and limitations.

`-foe true` stops on benchmark failure and produces a nonzero exit. The default
`-foe false` diagnoses failures and continues. Caught benchmark exceptions can
leave valid observations from earlier iterations or forks; actual populations may
be smaller than requested. A crashed or corrupt fork discards that configuration's
results. An entirely failed selection can return an empty result set.
Public Runner uses the same policy through `shouldFailOnError`. Caller
interruption and surviving embedded workers always stop the run.

## Result files

All backends accept `-rf text|csv|scsv|json|latex|html` with `-rff <path>`:

```text
examplesNative/Jmh/run LifecycleBenchmark.sum -rf scsv -rff results.scsv
examplesJS/Jmh/run LifecycleBenchmark.sum -rf text -rff results.txt
examplesJS/Jmh/run LifecycleBenchmark.sum -rf latex -rff results.tex
examplesJVM/Jmh/run LifecycleBenchmark.sum -rf html -rff results.html
```

Text files contain the aligned summary and percentile tables. CSV and SCSV
contain the same summary columns, separated by commas or semicolons, with quoted
fields. JSON retains raw measurements and backend metadata. LaTeX files contain
an escaped `tabular` fragment ready to include in a document, without extra
LaTeX packages. HTML produces a self-contained page with responsive summary and
percentile tables, light/dark styling, execution details, and expandable raw
measurements. ScalaTags Text generates and escapes the HTML on every backend;
viewing the report requires no scripts, network access, or external assets.
The JVM uses upstream JMH for measurements and JSON, CSV, SCSV, and text exports.
Its HTML and LaTeX exports use the shared renderers over JMH JSON. JMH 1.37
removes line breaks from JSON string fields, which these JVM exports inherit.
Select the format explicitly; the filename extension does not choose it.
The default is CSV. Supplying only `-rf json` writes `jmh-result.json`.
Portable options also include `-wm INDI|BULK|BULK_INDI`, `-wmb <include>`,
`-tg <distribution>`, `-opi`, `-si`, `-gc`, `-v` and `-o <path>`.
Use integer durations such as `500us`; thread counts accept positive integers
or `max`. JS/Wasm requires a single worker.

Public results can also be formatted after a programmatic run:

```scala
import scala.benchmark.{ResultFormatFactory, ResultFormatType}
ResultFormatFactory.getInstance(ResultFormatType.JSON, "results.json").writeOut(results)
```

A PrintStream overload leaves flushing and closing to the caller. Portable
formatting requires the runtime, accepts manually constructed public results and
uses the shared report renderers. The file overload writes UTF-8 and overwrites
on each write. See [result-format factory compatibility](RESULT_FORMAT_FACTORY_PARITY.md)
for metadata, JSON settings and presentation limits.

## Validation

```text
sbt --batch "compilerPlugin/test; runtimeJVM/test; sbtPlugin/scripted"
```

The `platform/{jvm,native,js,wasm}` scripted suites in `sbt-plugin/src/sbt-test`
check compatibility of the same benchmark sources across backends, consuming
locally published artifacts in isolated sbt builds. Run all platform checks with
`sbtPlugin/scripted platform/*`, or select one platform. They check facade-based JVM and Native benchmarks,
JS and Wasm execution, all four measurement modes,
threads/forks, fixtures, parameters, JSON/CSV output, failure/timeout handling,
and incremental benchmark rename/deletion. Short compatibility benchmarks and
fault probes live in `sbt-plugin/src/sbt-test-sources/scala`, independently of the
public performance examples, so iteration-count assertions and intentional
failures never enter ordinary example runs. Fixture build logic is shared from `sbt-plugin/src/sbt-test-common`.
The `reports/{jvm,native,js,wasm}` suites validate all six file formats, including
quoted multiline parameters, Unicode, LaTeX and HTML escaping, numeric values,
confidence intervals, raw measurements, and HTML output for all four modes.
Run both groups with `sbtPlugin/scripted platform/* reports/*`.
The scripted dependency task publishes the compiler plugin
selected by `compilerPlugin / scalaVersion` and libraries built with Scala 3.3.3.

```text
set compilerPlugin / scalaVersion := "3.10.0"
sbtPlugin/scripted platform/native
```

CI runs compilation and unit tests on Scala 3.3.9, cross-compiles compiler
plugins for every supported compiler, and runs compatibility and report scripted
suites separately for each platform. The workflow schedules Native scripted tests
on Linux, macOS and Windows (x86-64), including forks from executable paths
containing spaces and Unicode. JS and Wasm checks use Node.js 25. Local verification
and unverified host coverage are distinguished in the
[capability matrix](CAPABILITY_MATRIX.md); configuring a CI job is not evidence that
it has passed.

## Releases and provenance

Change `project/BuildSettings.scala` for releases and supported compiler
versions. sbt-buildinfo generates the plugin metadata from these build settings. Shared `_3` libraries publish only when compiled with Scala 3.3.3 to retain
TASTy compatibility; publication is skipped for other Scala versions. Compiler plugins
publish for each full compiler version, e.g. `benchmark-plugin_3.3.9`.
The sbt artifact remains `sbt-scala-benchmark`, independent of Scala Native's
version. The Native and JS runtime artifacts are published; the JVM execution-test backend is not.

Extracted from Scala Native commit `0eb84b58d` (`feature/native-jmh`). Relevant API
and algorithms derive from OpenJDK JMH 1.37, commit
`2effa2c8310e1d3ad03c8ee02024edca9252b46a`. See [LICENSE](LICENSE), [NOTICE](NOTICE),
and retained source headers for GPLv2 with Classpath Exception terms.
