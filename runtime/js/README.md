Scala.js runtime backend
========================

The Node.js host runs full-optimized Scala.js JavaScript or WebAssembly output.
Enable `ScalaJSPlugin` and `JmhPlugin` on a dedicated benchmark project; use
`Jmh/compile`, `Jmh/run -l`, or `Jmh/run <benchmark> <JMH options>`.

The runtime supports throughput, average time, sample time, and single-shot
measurements; warmup and measurement iterations; parameters; trial, iteration,
and invocation fixtures; discarded warmup forks; isolated measurement forks;
and JSON, CSV, SCSV, text, LaTeX, and HTML result files. JSON includes raw iteration
data or histograms. HTML combines summary and percentile tables with execution
metadata and expandable raw measurements. ScalaTags Text renders the standalone
page, which needs no scripts or external assets. Select the format with `-rf`
and the output path with `-rff`.
The shared Scala harness and statistics implement the same arithmetic as Native.

Only one benchmark thread per process is supported. Override `@Threads` with
`-t 1` when sharing a benchmark configured for several JVM/Native threads.
Concurrent JS workers and browser hosts are not implemented.

Timing uses Scala.js's monotonic `System.nanoTime` backed by the host performance
clock. The event loop polls the deadline every 64 operations instead of relying
on a timer callback that cannot execute during a synchronous benchmark loop.
The elapsed time includes this polling overhead. Invocation fixture time is
excluded from the score in the same way as on Native. Warmup remains necessary
for JS engine compilation and caches, including with Wasm output.

Blackholes call a host function outside the linked module, which stores one
externally observable value. This prevents the Scala.js optimizer from dropping
consumed computations and bounds retained state. Consumption includes the cost
of crossing that boundary; it is not a zero-cost engine intrinsic. Scala
`@noinline` and `@CompilerControl` constrain Scala.js optimization, not the host
engine's JIT decisions.

Fresh Node processes isolate forks. A separate supervisor watches per-iteration
deadlines and kills non-returning benchmark processes. Use forks for blocking or
potentially non-returning code; in-process execution (`-f 0`) cannot interrupt a
blocked event loop. JSON reports label the backend `js` or `wasm` and include
Node/V8 versions, engine options, CPU, OS, linker optimization, and clock
calibration under `javascript`.

WebAssembly
-----------

[Scala.js 1.22.0's stable Wasm backend](https://www.scala-js.org/doc/project/webassembly.html)
uses the same runtime artifact, discovery,
blackhole, measurement loops, host, and fork protocol. Enable it in the benchmark
project; Node.js 25 or another compatible host engine is required:

```scala
import org.scalajs.linker.interface.{ESVersion, ModuleKind}

scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)
  .withESFeatures(_.withESVersion(ESVersion.ES2022).withUseWebAssembly(true)))
```

The host imports the linked ES module dynamically, after installing its services.
No `@JSExport` methods or JSPI are required. The `platform/wasm` scripted suite
executes actual Wasm output, including all measurement modes and isolated forks.
Node executable/options can be set with `benchmarkNode` and
`benchmarkNodeOptions` (from `ScalaJSBenchmarkPlugin.autoImport`).
