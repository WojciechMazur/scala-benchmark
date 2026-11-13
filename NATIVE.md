# Microbenchmarking

Native JMH runs Scala 3 microbenchmarks using the JMH 1.37 annotation API. The
compiler plugin generates typed Scala harnesses, including direct benchmark
calls inside measurement loops. The executable coordinates its own worker
threads and fresh process forks; a JVM is not required to run a linked benchmark.

The initial compiler compatibility matrix is Scala 3.3.3–3.3.9, 3.9.0, and 3.10.0 on Linux,
macOS, and Windows (x86-64). Scala 2 is not supported by this harness.
On Windows, install LLVM/Clang and the Visual Studio C++ tools with a Windows
SDK, and run sbt from a developer command prompt. CI uses LLVM 20.1.4 and checks
both disabled LTO and ThinLTO.

## Set up a benchmark project

Add the standalone benchmarking plugin to `project/plugins.sbt` (its version is
independent of the Scala Native version):

```scala
addSbtPlugin("org.scala-native" % "sbt-scala-benchmark" % benchmarkVersion)
```

Enable it on a dedicated Native benchmark project:

```scala
lazy val benchmarks = project
  .enablePlugins(ScalaNativePlugin, JmhPlugin)
  .settings(scalaVersion := "3.3.9")
```

Put benchmark definitions in `src/main/scala`, or share that source directory
with a JVM benchmark project using upstream sbt-jmh. The Native plugin supplies
the Native compatibility API and compiler plugin. Do not add JVM `jmh-core` to
the Native classpath: it defines the same API names but has no Native code.

```scala
package example

import java.util.concurrent.TimeUnit
import scala.benchmark.*

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class SumBenchmark {
  @Param(Array("16", "1024")) var size: Int = 0
  private var values: Array[Int] = _

  @Setup(Level.Trial)
  def prepare(): Unit = values = Array.tabulate(size)(identity)

  @Benchmark
  def sum(): Int = {
    var result = 0
    var i = 0
    while (i < values.length) {
      result += values(i)
      i += 1
    }
    result
  }
}
```

Returned values are automatically consumed. Methods returning `Unit` must
perform observable work or explicitly consume intermediate results through an
injected `org.openjdk.jmh.infra.Blackhole`. `Blackhole.consumeCPU` provides a
synthetic workload, not a calibrated duration or cycle count.

## Run and export

```text
benchmarks/Jmh/run -l
benchmarks/Jmh/run -lp
benchmarks/Jmh/run SumBenchmark -wi 3 -i 5 -w 1s -r 1s -f 3 -t 1
benchmarks/Jmh/run SumBenchmark -p size=1024 -rf json -rff results.json
benchmarks/Jmh/nativeLink
```

The linked executable accepts the same options directly. Use `-h` for the
supported options. It embeds the benchmark inventory and build configuration.
Other build tools can supply generated module names using `--suite`; module
names end in `$nativejmh$bootstrapper$`.

JMH's defaults are intentionally retained: throughput, one thread, five warmup
iterations of ten seconds, five measured iterations of ten seconds, and five
fresh processes. SingleShot defaults to no warmup and one measured iteration.
Method annotations override class annotations; command-line options override
annotations. Warmup forks are discarded. `-f 0` disables process isolation and
is intended for diagnosis.

The plugin defaults to `release-full`, multithreading enabled and embedded
resources. Set `Compile / nativeConfig` explicitly to choose another release
mode, LTO strategy or collector. Compare results only with the recorded build
settings in mind.

Console output includes the score and its 99.9% Student-t confidence interval.
JSON preserves per-fork iteration scores or quantized sample histograms; CSV
and semicolon-separated SCSV provide summaries. `-rf text` saves the aligned
console summary and percentile tables; `-rf latex` produces an escaped LaTeX
`tabular` fragment. `-rf html` produces a self-contained report with summary and
percentile tables, execution metadata, and expandable raw measurements, rendered
with ScalaTags Text. All six formats use `-rff <path>`. HTML reports need no
scripts or external assets. The JSON metric envelope follows JMH's conventions, with
additional Native metadata and explicit unavailable JVM fields. Results identify
the Native harness rather than claiming to have been produced by upstream JMH.
The conventional confidence interval is a statistical summary, not a guarantee
of accuracy or independence between iterations.

## Compatibility

Supported measurement modes are Throughput, AverageTime, SampleTime and
SingleShotTime; `Mode.All` expands to all four. State scopes are Thread and
Benchmark. Setup and teardown support Trial, Iteration and Invocation levels,
inherited fixtures and acyclic state dependencies. Benchmark and fixture
arguments may be states, Blackhole or ThreadParams.

`@Param` supports mutable fields with primitive, boxed primitive, String and
Java-compatible enum types. Values are assigned after construction and before
trial setup, so constructors must not depend on them. Multiple parameters form
a Cartesian product. A private parameter field's defining state must also be
compiled with the plugin, which generates an assignment bridge.

Benchmark definitions use concrete classes at package scope, with public methods
and no-argument constructors. State classes may also be declared inside objects.
Generic benchmark methods with erased state bounds and overloaded methods in
separate groups are supported. Ordinary inner classes requiring an enclosing
instance remain unsupported.

Native implements Groups, GroupThreads, Scope.Group, AuxCounters,
BenchmarkParams, IterationParams, Control and the Runner/OptionsBuilder API.
Public custom OutputFormat Runner integration is implemented, including genuine
fork callbacks and report updates. Profilers are excluded.
See [the capability matrix](CAPABILITY_MATRIX.md) for verification and limits.

CompilerControl.INLINE and DONT_INLINE map to existing Native compiler controls.
Other CompilerControl modes are rejected. JVM-specific Fork settings, including
`jvmArgs`, compile with warnings and are recorded as ignored settings. They do
not configure the Native heap, collector or optimizer.

Invocation fixtures require per-invocation timestamps and can add substantial
overhead. Shared invocation fixtures also require arbitration. Prefer Trial or
Iteration fixtures whenever they describe the workload correctly. In
SingleShot mode a batch is one reported operation; use
OperationsPerInvocation when a benchmark performs multiple logical operations.

## Measurement discipline

Native still needs warmup for caches, allocation and GC, and fresh processes to
observe run-to-run variation. Blackholes retain consumed computations through
Interflow and LLVM, including LTO, but cannot repair constant inputs or an
incorrectly designed benchmark. Very small workloads may be dominated by the
Native sink's external-call overhead. Clock calibration is reported separately;
the harness does not subtract an empty-loop baseline.
SingleShot measurements below the clock's resolution can report zero. Increase
the batch size when a single invocation is too short to time reliably.

Use a quiet machine, record CPU and power configuration, inspect variation
between forks, and inspect optimized code for critical benchmarks. Ordinary CI
checks lifecycle and measurement arithmetic; performance comparisons belong on
controlled hardware.

## Developing the harness

```text
sbt --batch "runtimeJVM/test; compilerPlugin/test; examplesNative/Jmh/run -l"
sbt --batch "++ 3.9.0; runtimeJS/compile"
```

The deterministic JVM tests exercise the Native harness's portable logic. JVM
performance benchmarks themselves should use upstream JMH, not the JVM test
backend. The sbt scripted tests run the generated Native examples, parse JSON and CSV,
and check process failure handling without narrow timing thresholds.

Publish the shared Native runtime with Scala 3.3.3, the minimum supported
compiler, so its TASTy remains readable by all supported versions. The
compiler plugin is published separately for each full Scala version. The JVM
runtime backend is only a test dependency and is not published.

The compatibility API and relevant harness algorithms are adapted from OpenJDK
JMH 1.37. See `runtime/LICENSE`, `runtime/NOTICE`, `interface/LICENSE` and
`compiler-plugin/LICENSE` for the retained GPLv2 with Classpath Exception notices.
