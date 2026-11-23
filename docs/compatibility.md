# Compatibility

SMH runs JMH 1.37 benchmarks written in Scala 3 on the JVM, Scala Native, Scala.js and Scala.js
WebAssembly. On the JVM, benchmarks are generated and run by upstream JMH; SMH only adds platform
selection (`@Platforms`) and the HTML and LaTeX result formats. On the other platforms, a macro derives the
benchmark harness at compile time and the SMH runtime executes it.

## Platforms

| | JVM | Native | JS | Wasm |
| --- | --- | --- | --- | --- |
| Harness | upstream JMH | derived by macro | derived by macro | derived by macro |
| Benchmark threads | any | any | 1 | 1 |
| Forks | JVM processes | fresh processes | fresh Node.js processes | fresh Node.js processes |
| Process containment | JMH | process groups (POSIX), job objects (Windows) | process groups (POSIX), best effort on Windows | as JS |
| Collection requests (`-gc`) | yes | yes | needs `--expose-gc` | needs `--expose-gc` |
| Profilers (`-prof`) | yes | no | no | no |

## API

| Feature | JVM | Native | JS / Wasm |
| --- | --- | --- | --- |
| Modes: throughput, average, sample, single shot | yes | yes | yes |
| Batching and operations per invocation | yes | yes | yes |
| `@State` scopes `Thread`, `Benchmark`, `Group` | yes | yes | yes, one worker |
| `@Setup` / `@TearDown` at every level, dependent states | yes | yes | yes |
| `@Group` and `@GroupThreads` | yes | yes | one active worker |
| `@Param`, including enums and overrides | yes | yes, non-`private` fields | yes, non-`private` fields |
| `@AuxCounters` | yes | yes | yes |
| `Blackhole`, `BenchmarkParams`, `IterationParams`, `ThreadParams`, `Control` | yes | yes | yes |
| `@Fork(jvmArgs = ...)` and other JVM options | yes (also with `microbench.run`) | ignored, with a warning | ignored, with a warning |
| `Runner`, `OptionsBuilder`, command-line options | yes | yes | yes |
| Custom `OutputFormat` | yes | yes | yes |
| Result formats: text, CSV, SCSV, JSON, LaTeX, HTML | yes | yes | yes |
| Bulk and individual warmup, extra warmup benchmarks | yes | yes | yes |
| `@Platforms` restrictions | yes | yes | yes |

## Running without sbt

`microbench.run(args)(suite[A], suite[B])` runs benchmarks from any build tool, such as scala-cli. Benchmark
classes are listed explicitly: Scala Native and Scala.js cannot enumerate classes at run time, and only the build
tool knows which classes a compilation contains.

| | JVM | Native | JS / Wasm |
| --- | --- | --- | --- |
| Harness | derived by macro, as on Native | as with sbt | as with sbt |
| Forks | the same main class and class path, in a fresh JVM | as with sbt | the embedded Node.js host |
| Build information in results | Java version and vendor | Scala Native version, release or debug mode, GC, multithreading | Scala.js version, backend, optimization |

On the JVM, forks use JMH's compiler blackholes when the JVM supports them, apply `@CompilerControl` and
`@Fork(jvmArgs, jvmArgsAppend, jvmArgsPrepend)` as HotSpot options, and accept `-jvm`, `-jvmArgs`,
`-jvmArgsAppend` and `-jvmArgsPrepend`. Upstream JMH remains the reference on the JVM: it has profilers and more
JVM-specific measures against false sharing and loop optimizations. With sbt, the JVM keeps using upstream JMH.

Scala.js benchmarks started with plain `node`, as scala-cli does, start the Node.js host themselves from a temporary
directory, with the same Node.js options.

## Differences from JMH

| Topic | Behaviour off the JVM |
| --- | --- |
| `private` `@Param` fields | Rejected at compile time. Use a public or `private[pkg]` `var`. |
| `@CompilerControl(DONT_INLINE)` on a benchmark method | The call is kept out of the measured loop. |
| `@CompilerControl(INLINE)` | Advisory; add Scala's `@inline` as well. Warned at compile time. |
| `@CompilerControl` on other methods | No effect; use `@noinline` or `@inline`. Warned at compile time. |
| Other `@CompilerControl` modes | Rejected at compile time. |
| Benchmark classes | Discovered in the benchmark project's own sources, not in dependencies. |
| Inner classes needing an outer instance, benchmarks declared in objects | Not supported. |
| Failures in custom `OutputFormat` callbacks | Fatal; JMH may ignore them. |
| Failures after a trial has completed | Fatal, as in JMH. |
| Supporting APIs | A subset of `org.openjdk.jmh`; the statistics types do not reproduce Commons Math exceptions. |
