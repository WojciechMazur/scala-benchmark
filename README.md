# Scala MicroBenchmark Harness (SMH)

Run JMH benchmarks written in Scala 3 on the JVM, Scala Native, Scala.js and Scala.js WebAssembly, from the same
sources.

- **JVM:** benchmarks are generated and measured by upstream JMH 1.37, unchanged.
- **Native, JS and Wasm:** a macro derives each benchmark's harness at compile time, with the benchmark call
  inlined into the measured loop, and the SMH runtime executes it.

See [compatibility](docs/compatibility.md) for supported versions, features per platform and the differences
from JMH.

## Setup

Add the plugin to `project/plugins.sbt`, next to sbt-jmh and the plugins of the platforms you need:

```scala
addSbtPlugin("io.github.wojciechmazur" % "sbt-microbench" % "<version>")
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.8")
addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.13")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.23.0")
```

Enable `JmhPlugin` on the benchmark project. The plugin adds the right backend for each platform:

```scala
val scalaVersions = Seq("3.3.9")

lazy val benchmarks = projectMatrix
  .in(file("benchmarks"))
  .enablePlugins(JmhPlugin)
  .jvmPlatform(scalaVersions)
  .nativePlatform(scalaVersions)
  .jsPlatform(scalaVersions)
```

## Write benchmarks

Benchmarks use the JMH annotations. Import them from `microbench`, or keep existing `org.openjdk.jmh`
imports:

```scala
import microbench.*

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
class Example:
  @Param(Array("8", "64")) var size: Int = 0

  @Benchmark def run(blackhole: Blackhole): Int =
    blackhole.consume(size)
    size
```

`@Platforms` restricts where a class or method runs. Class, inherited and method restrictions intersect, and every
body must still compile on every platform:

```scala
@Platforms(Array(Platform.JVM, Platform.Native), reason = "Needs concurrent group workers")
class ConcurrentBenchmarks:
  @Benchmark @Group("workers") def read(): Int = 1
  @Benchmark @Group("workers") def write(): Int = 2
```

Wide selections skip benchmarks that cannot run on the current platform. Naming such a benchmark exactly is an
error.

## Run

`Jmh/run` takes the JMH command-line options on every platform:

```text
sbt "benchmarksNative/Jmh/run -l"
sbt "benchmarksNative/Jmh/run Example -wi 3 -i 5 -f 2"
sbt "benchmarksJS/Jmh/run Example -rf json -rff results.json"
sbt "benchmarks/Jmh/run Example -rf html -rff results.html"
```

Result formats are `text`, `csv`, `scsv`, `json`, `latex` and `html`. The programmatic API is available too:
`microbench.Runner` with `OptionsBuilder`, as in JMH.

## Run with scala-cli

Without sbt, list the benchmark classes in a main method. `suite[T]` derives a class's harness at compile time, and
`run` takes the same command-line options as `Jmh/run`:

```scala
//> using dep io.github.wojciechmazur::microbench::<version>
//> using nativeMode release-full
//> using nativeMultithreading
//> using jsMode release
//> using nativeVersion 0.5.13
//> using jsVersion 1.23.0

import microbench.{run, suite}

@main def benchmarks(args: String*): Unit = run(args)(
  suite[Example],
  suite[ConcurrentBenchmarks]
)
```

`nativeVersion` and `jsVersion` must be at least the Scala Native and Scala.js versions the library is built with.

```text
scala-cli run . -- -l
scala-cli run . --native -- Example -wi 3 -i 5 -f 2
scala-cli run . --js -- Example -rf json -rff results.json
```

The same runtime then measures on every platform, including the JVM. Unlike `Jmh/run`, the JVM path uses SMH's own
harness rather than upstream JMH; see [compatibility](docs/compatibility.md#running-without-sbt).
The `nativeMode` and `jsMode` directives matter: a debug build runs, but warns that its measurements are not
representative. [examples/scala-cli](examples/scala-cli) is a complete project.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

GPLv2 with the Classpath Exception: linking benchmarks with this library does not put them under the GPL. The JMH
API and measurement semantics are ported from OpenJDK JMH 1.37. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
