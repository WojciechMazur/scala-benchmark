package example

import microbench.{run, suite}

@main def benchmarks(args: String*): Unit = run(args)(
  suite[Example]
)
