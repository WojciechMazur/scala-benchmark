# Optimized measurement integrity

The local integrity suites check representative optimized workloads on Native,
JS and Wasm. They supplement the JMH 1.37 execution comparisons; they do not
establish that arbitrary benchmark bodies are immune to optimization.

## What the checks establish

A shared fixture covers all eight primitive return types, a returned allocation,
explicit primitive/reference Blackhole calls, observable Unit work, and an
explicitly inlined computation. A single-shot batch makes the expected work and
consumption populations deterministic. The fixture's parameter is resolved by the
normal runner, rather than substituted into a handwritten measurement loop.

- Native uses the plugin's release-full build. The suite retains the executable,
  selected final disassembly, optimized LLVM functions and NIR excerpts. It checks
  measured workload calls, returned-value flow into consumption, arithmetic,
  allocation/payload stores, consumption barriers and clock calls. Separate suites
  select no LTO, ThinLTO and full LTO.
- JS and Wasm use fullLinkJS and the production Node launcher. A test-only preload
  observes the external host consumption calls and clock reads. It checks exact
  populations, JS scalar values and distinct allocation identities. Wasm Longs
  cross this host boundary as opaque boxed references; the observer checks their
  population, and the WAT inspection checks the corresponding arithmetic and
  conversion paths.
- Wasm additionally emits WAT from the same linker invocation as the executed
  binary. Checks inspect workload calls, arithmetic, allocation and payload
  initialization, consumption and clock reads. Pretty printing enables this text
  output without disabling full optimization; see the pinned
  [Scala.js 1.22.0 backend](https://github.com/scala-js/scala-js/blob/v1.22.0/linker/shared/src/main/scala/org/scalajs/linker/backend/WebAssemblyLinkerBackend.scala).
- Native and Wasm inspectors also reject deliberately damaged copies of the
  observed artifacts: missing work, allocation/payload, consumption or clocks.

The JS/Wasm suites run the normal production host first, then repeat the linked
fixture with the observer. Observation changes the host's behavior and can affect
JIT decisions; these checks do not inspect V8's final tiered machine code. The
retained linked code and the independent execution reference checks are separate
evidence, rather than a claim that instrumentation is performance-neutral.

Evidence is retained under `target/execution-parity/integrity/<backend-or-variant>`.
It includes result JSON, inspected code, runtime/build metadata, and SHA-256
fingerprints of fixtures, libraries, plugin, observation/inspection code and
artifacts. These are instrumented semantic checks; their timing values are not
performance measurements to compare across platforms.

## What returning an object does not guarantee

The initial fixture returned a plain heap object whose payload field was never read
anywhere. The JS/Wasm linker removed that field and left an empty constructor.
The allocation and its identity remained observable to the host Blackhole, but
initialization arithmetic feeding only that unused field was no longer protected
from later optimization. The retained reproduction is in
`target/execution-parity/integrity-limitations/unused-payload`.

The positive allocation fixture makes the payload observable through a separate
non-inlined observer used in trial setup. This preserves the payload access while
keeping payload reads outside measurement. The optimized allocation and payload
stores are then inspected explicitly. This is a scoped check, not a general
solution for every returned object graph.

When benchmarking computation that produces a value, return or explicitly consume
that value. Returning an object whose contents are unused can preserve allocation
without preserving every computation used to initialize it. Likewise, Unit work
with no observable effect can disappear. Inspect optimized code for workloads
where this distinction matters; Blackhole consumption cannot promise preservation
of every source-level operation. This repository does not add reflective traversal
or hashing of arbitrary result objects to the measured region.

## Local acceptance

The 8 November 2026 macOS run passed all five suites: JS, Wasm, Native without
LTO, Native ThinLTO and Native full LTO. Each produced 12 successful benchmark
results. JS/Wasm normal and observed runs passed; the saved runnable bundles were
also rerun outside the temporary scripted projects. Retained hashes matched the
provenance records. This is local evidence, with the test-specific Native settings
below; it does not claim Linux/Windows or final V8 machine-code acceptance.

## Running locally

```sh
sbt --batch 'reload; sbtPlugin/scripted integrity/*'
```

The pinned Native 0.5.12 fixtures explicitly include `<cstdlib>` for this macOS
SDK/toolchain combination. The ThinLTO fixture also roots six vendored libunwind
symbols requested by Apple ld. These settings are confined to the test builds and
are captured by the build-source fingerprints/link options; they do not change
the plugin's default build settings or establish general ThinLTO compatibility.

Native inspection requires `llvm-objdump` on PATH, or `LLVM_OBJDUMP` pointing to
it. The suites use the existing JDK, Native and Node requirements. Platform and
compiler coverage is limited to the local outcomes recorded in WORK_SUMMARY.md;
Linux/Windows, other engines and other optimization/GC combinations require their
own acceptance runs.
