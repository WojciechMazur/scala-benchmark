# Contributing

## Requirements

- JDK 17 or later
- sbt 2.0
- LLVM/Clang, for Scala Native
- Node.js 25, for Scala.js and Wasm

## Modules

| Module | Contents |
| --- | --- |
| `interface` | JMH API for Native and JS, the `scala.benchmark` facade, the JVM adapter and the inventory macro |
| `runtime` | the harness macro and the Native/JS runtime |
| `sbt-plugin` | sbt integration and the Node.js host |
| `examples` | example benchmarks for every platform |

Platform-specific sources live next to the shared ones: `shared`, `jvm`, `native`, `js`, plus `non-jvm` (Native
and JS) and `jvm-native` (the JVM unit tests and Native).

## Tests

```text
sbt runtime/test
node --test sbt-plugin/src/test/node/*.test.mjs
sbt "scriptedOn jvm,native,js benchmark/*"
sbt "scriptedOn wasm benchmark/integrity benchmark/platform"
```

The scripted tests in `sbt-plugin/src/sbt-test/benchmark` run each scenario on every selected platform and compare
the results with upstream JMH on the JVM. `scriptedOn` selects the platforms; add `scala=<version>` to build the
test projects with a newer compiler.

## Formatting

Format with `scalafmt` before committing; CI checks it.

## Licensing

Files ported from JMH keep Oracle's licence header. Every other source file starts with the project header, which
grants the Classpath Exception; copy it from any existing file. Do not copy code from sources under licences
incompatible with GPLv2.
