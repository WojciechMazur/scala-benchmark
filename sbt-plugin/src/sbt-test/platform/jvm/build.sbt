lazy val benchmarks = FixtureBuild
  .matrix("jvm")
  .settings(pl.project13.scala.sbt.JmhPlugin.JmhKeys.Jmh / javaOptions += "-Djmh.ignoreLock=true")
lazy val root = project
  .in(file("."))
  .settings(
    FixtureBuild.settings(benchmarks.jvm(sys.props("benchmark.scala.version")), "jvm")
  )
