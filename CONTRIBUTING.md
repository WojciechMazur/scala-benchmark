# Contributing

## Requirements

- JDK 17 or later
- sbt 2.0
- LLVM/Clang, for Scala Native
- Node.js 25, for Scala.js and Wasm

## Modules

| Module | Contents |
| --- | --- |
| `interface` | JMH API for Native and JS, the `microbench` facade, the JVM adapter and the inventory macro |
| `runtime` | the harness macro, the `microbench.run` entry point and the runtime of every platform |
| `microbench` | no code: the single `microbench` dependency on the interface and the runtime |
| `sbt-plugin` | sbt integration |
| `examples` | example benchmarks for every platform |

Platform-specific sources live next to the shared ones: `shared`, `jvm`, `native`, `js`, plus `non-jvm` (Native
and JS) and `jvm-native` (JVM and Native). The Node.js host lives in `runtime/js/src/main/resources/microbench`;
the sbt plugin ships it as a resource, and the Scala.js runtime embeds it for launchers other than sbt.

## Tests

```text
sbt runtime/test
node --test sbt-plugin/src/test/node/*.test.mjs
sbt "scriptedOn jvm,native,js microbench/*"
sbt "scriptedOn wasm microbench/integrity microbench/platform"
```

To check the scala-cli entry point, publish the libraries locally under the version `examples/scala-cli` uses:

```text
sbt 'set ThisBuild / version := "1.0.0"' publishing/publishLocal
scala-cli run examples/scala-cli -- -l
scala-cli run examples/scala-cli --native -- LifecycleBenchmark.sum -wi 0 -i 1 -r 100ms -f 1
scala-cli run examples/scala-cli --js -- LifecycleBenchmark.sum -wi 0 -i 1 -r 100ms -f 1
```

The scripted tests in `sbt-plugin/src/sbt-test/microbench` run each scenario on every selected platform and compare
the results with upstream JMH on the JVM. `scriptedOn` selects the platforms; add `scala=<version>` to build the
test projects with a newer compiler.

## Releasing

Bump the version in the README and `examples/scala-cli/project.scala`, then push a tag; the `Release` workflow
publishes it to Maven Central.

```text
git tag -a v1.2.3 -m "v1.2.3"
git push origin v1.2.3
```

## Formatting

Format with `scalafmt` before committing; CI checks it.

## Licensing

Files ported from JMH keep Oracle's licence header. Every other source file starts with the project header, which
grants the Classpath Exception; copy it from any existing file. Do not copy code from sources under licences
incompatible with GPLv2.
