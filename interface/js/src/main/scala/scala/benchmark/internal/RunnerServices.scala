package scala.benchmark.internal

@scala.scalajs.reflect.annotation.EnableReflectiveInstantiation
trait PlatformRunnerService

object RunnerServices {
  def current: RunnerService = scala.scalajs.reflect.Reflect
    .lookupLoadableModuleClass("scala.scalanative.jmh.RunnerBridge$")
    .getOrElse(throw new IllegalStateException("The benchmark runtime is missing from the application"))
    .loadModule()
    .asInstanceOf[RunnerService]
}
