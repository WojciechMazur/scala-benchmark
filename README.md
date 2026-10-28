# scala-benchmark

Scala 3 benchmarking for the JVM, Scala Native, and Scala.js, developed under
`org.scala-native`. This is the standalone starting point extracted from the
Scala Native JMH implementation, not yet a complete multiplatform runner.

Requires JDK 17+, sbt 2.0.11, and LLVM/Clang for Native examples.
The build uses released Scala Native 0.5.12 and Scala.js 1.22.0 plugins;
it does not depend on the neighboring Scala Native checkout.
Scala 3.3.8 and 3.9.0 are the initial compiler targets. The sbt plugin itself
uses sbt 2's Scala 3.8.4.

## Run the examples

```text
sbt
> nativeExamples/Jmh/run -l
> nativeExamples/Jmh/run LifecycleBenchmark.sum -p size=16 -wi 1 -i 3 -w 1s -r 1s -f 2
> jvmExamples/Jmh/run .*LifecycleBenchmark.sum -p size=16 -wi 1 -i 3 -w 1s -r 1s -f 2
```

Both example projects compile the same sources in `examples/shared`.
The JVM examples use upstream JMH. Native examples use the extracted compiler
plugin and runtime. The JVM runtime backend is currently for deterministic
harness tests, not production measurements.

Use `++3.9.0` to switch to the other supported Scala version. Do not force
Scala versions with `++...!`: the sbt plugin has its own compiler version.
See [NATIVE.md](NATIVE.md) for supported annotations and measurement semantics.

The sbt integration depends on upstream sbt-jmh and reuses its `Jmh`
configuration, `Jmh / run`, and generation task. Enable `JmhPlugin` alongside
`ScalaNativePlugin` or `ScalaJSPlugin`; the backend adapter is enabled
automatically. JVM projects retain upstream sbt-jmh behavior. The Native
adapter replaces JVM bytecode generation with compiler-generated Native
harnesses and removes upstream JVM-only JMH dependencies. Sources in
`src/jmh/scala` are included in the Native compilation as well as `src/main`.
The Native adapter compiles both source directories in `Compile`, and
`Jmh / compile` delegates to that compilation. Configure Native benchmark
dependencies and `nativeConfig` in `Compile` on a dedicated benchmark project.
JVM-only generator selection and Java fork options are not Native compiler
settings. `Jmh / run -l` lists benchmarks on both working backends.
`jsExamples/Jmh/run` currently reports an explicit unsupported-backend error.

## Modules

| Project IDs | Purpose |
| --- | --- |
| `coreJVM`, `coreNative`, `coreJS` | Portable statistics and sampling histograms |
| `jmhApiJVM`, `jmhApiNative`, `jmhApiJS` | JMH annotation compatibility API and thread metadata |
| `runtimeJVM`, `runtimeNative` | Shared lifecycle/measurement code and platform implementations |
| `runtimeJS` | Scala.js backend scaffold, depending on portable modules; runner pending |
| `compilerPlugin` | Harness generator, published for each full Scala compiler version |
| `sbtScalaBenchmark` | sbt-jmh backend adapters for sbt 2 |
| `nativeExamples`, `jvmExamples` | Shared example sources executed by their respective harnesses |

The `org.openjdk.jmh` compatibility classes must not be added alongside
upstream `jmh-core` on the JVM. The upstream JVM example has no dependency on
our compatibility classes. Blackhole currently lives in the runtime because
its implementation is platform-specific.

The implementation retains `scala.scalanative.jmh` package names during
extraction. Public Scala-idiomatic APIs, a possible `scala.benchmark` facade,
and a stable generated-harness ABI are future design work.

## Validate

```text
sbt --batch "compilerPlugin/test; runtimeJVM/test; runtimeJS/compile; sbtScalaBenchmark/compile; jvmExamples/Jmh/compile; nativeExamples/Jmh/run -l"
sbt --batch "++3.9.0; compilerPlugin/test; runtimeJVM/test; runtimeJS/compile"
```

Scala.js currently compiles the portable core and annotation API only. Node.js
measurement loops, blackholes, discovery, and process isolation remain to be
implemented. No Scala.js measurement accuracy or JMH concurrency parity is
claimed by this initial extraction.

The full Native integration suite validates all four modes, forks, fixture
lifecycles, parameters, failures, timeouts, JSON/CSV output, and incremental
benchmark discovery:

```text
python3 scripts/check.py --scala-version 3.3.8 --incremental
```

## Development and releases

The project has its own version, independent of Scala Native. Change
`project/BenchmarkVersion.scala` when cutting a release. Publish the shared
runtime and API using Scala 3.3.8 so their TASTy is readable by both supported
versions; publish `compilerPlugin` separately for each supported full compiler
version. The compiler-plugin and generated-runtime ABI must remain compatible.
The test-only JVM runtime and unfinished Scala.js runtime are not published.

Repository-dispatch integration and automated publication can be added after
the standalone artifact layout and compatibility checks are settled.

## Provenance and license

Extracted from `scala-native/scala-native` commit `0eb84b58d` on branch
`feature/native-jmh`. The JMH compatibility API and relevant algorithms derive
from OpenJDK JMH 1.37, commit
`2effa2c8310e1d3ad03c8ee02024edca9252b46a`.
Retained GPLv2 with Classpath Exception terms and notices are in [LICENSE](LICENSE),
[NOTICE](NOTICE), and the original source headers.
