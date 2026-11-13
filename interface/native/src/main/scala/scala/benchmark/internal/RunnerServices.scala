package scala.benchmark.internal

@scala.scalanative.reflect.annotation.EnableReflectiveInstantiation
trait PlatformRunnerService

object RunnerServices:

  def current: RunnerService = scala.scalanative.reflect.Reflect
    .lookupLoadableModuleClass("scala.benchmark.runtime.RunnerBridge$")
    .getOrElse(throw new IllegalStateException("The benchmark runtime is missing from the application"))
    .loadModule()
    .asInstanceOf[RunnerService]
