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

To check the scala-cli entry point, publish the libraries locally and run the example on each platform:

```text
sbt publishExampleLibraries
scala-cli run examples/scala-cli -- -l
scala-cli run examples/scala-cli --native -- LifecycleBenchmark.sum -wi 0 -i 1 -r 100ms -f 1
scala-cli run examples/scala-cli --js -- LifecycleBenchmark.sum -wi 0 -i 1 -r 100ms -f 1
```

The scripted tests in `sbt-plugin/src/sbt-test/microbench` run each scenario on every selected platform and compare
the results with upstream JMH on the JVM. `scriptedOn` selects the platforms; add `scala=<version>` to build the
test projects with a newer compiler.

## Releasing

Releases are published to Maven Central from a tag; the version is the tag without its `v` (sbt-dynver). Pick the
number by the policy in the README's "Versioning" section.

1. Push the release commit to `main` and wait for CI to pass.
2. Update the versions in the README, then tag that commit and push the tag:

   ```text
   git tag -a v1.2.3 -m "v1.2.3"
   git push origin v1.2.3
   ```

3. The `Release` workflow signs the artifacts, uploads them to the Central Portal and releases them. They appear on
   Maven Central within about half an hour.

A Central release cannot be removed or replaced: a broken release is fixed by the next patch version. Never run
`ci-release`, `sonaUpload` or `sonaRelease` locally.

## Formatting

Format with `scalafmt` before committing; CI checks it.

## Licensing

Files ported from JMH keep Oracle's licence header. Every other source file starts with the project header, which
grants the Classpath Exception; copy it from any existing file. Do not copy code from sources under licences
incompatible with GPLv2.
