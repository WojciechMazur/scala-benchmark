// Benchmarks run directly with scala-cli, on the JVM, Scala Native or Scala.js:
//   scala-cli run examples/scala-cli -- -l
//   scala-cli run examples/scala-cli --native -- LifecycleBenchmark -wi 1 -i 3 -f 1
//   scala-cli run examples/scala-cli --js -- LifecycleBenchmark -wi 1 -i 3 -f 1
//> using scala 3.lts
//> using dep io.github.wojciechmazur::microbench::1.0.0
//> using files ../shared/src/main/scala

// Measure optimized code: these only apply to the platform being built.
//> using nativeMode release-full
//> using nativeMultithreading
//> using jsMode release

import microbench.{run, suite}
import microbench.examples.*

@main def benchmarks(args: String*): Unit = run(args)(
  suite[LifecycleBenchmark],
  suite[ParameterBenchmark],
  suite[FacadeBenchmark]
)
