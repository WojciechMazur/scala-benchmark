package scala.scalanative.jmh.plugin

import dotty.tools.dotc.plugins.{PluginPhase, StandardPlugin}

final class NativeJmhPlugin extends StandardPlugin {
  val name = "scalanative-jmh"
  val description =
    "Generates typed JMH-compatible benchmark harnesses"

  @annotation.nowarn("cat=deprecation")
  override def init(options: List[String]): List[PluginPhase] =
    List(
      new FacadeAnnotations,
      new PlatformEligibility,
      new BenchmarkHarnesses(
        nativeBackend = !options.contains("backend=js"),
        jvmBackend = options.contains("backend=jvm")
      )
    )
}
