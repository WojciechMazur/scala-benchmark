package scala.scalanative.jmh.plugin

import dotty.tools.dotc.plugins.{PluginPhase, StandardPlugin}

final class NativeJmhPlugin extends StandardPlugin {
  val name = "scalanative-jmh"
  val description =
    "Generates typed JMH-compatible Scala Native benchmark harnesses"

  @annotation.nowarn("cat=deprecation")
  override def init(options: List[String]): List[PluginPhase] = List(
    new BenchmarkHarnesses
  )
}
