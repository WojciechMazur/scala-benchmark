package org.openjdk.jmh.runner.options

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.results.format.ResultFormatType
import org.openjdk.jmh.util.Optional
import scala.jdk.CollectionConverters.*

class OptionsBuilder extends Options with ChainedOptionsBuilder {
  private var inherited: Options = null
  private val values = scala.collection.mutable.Map.empty[String, Any]
  private var includes = Vector.empty[String]
  private var excludes = Vector.empty[String]
  private var warmups = Vector.empty[String]
  private val modes = new java.util.TreeSet[Mode]((a, b) => java.lang.Integer.compare(a.ordinal, b.ordinal))
  private val parameters = scala.collection.mutable.Map.empty[String, java.util.ArrayList[String]]
  def parameterNames(): Vector[String] =
    (parameters.keys.toVector ++ (inherited match {
      case builder: OptionsBuilder => builder.parameterNames()
      case _                       => Vector.empty
    })).distinct
  def build(): Options = this
  def parent(options: Options): ChainedOptionsBuilder = {
    require(options != null && (options ne this), "Invalid parent options")
    inherited = options; this
  }
  private def get[T](key: String, fallback: Options => Optional[T]): Optional[T] =
    values
      .get(key)
      .map(v => Optional.of(v.asInstanceOf[T]))
      .getOrElse(if (inherited == null) Optional.none[T]() else fallback(inherited))
  private def merged(own: Vector[String], fallback: Options => java.util.List[String]): java.util.List[String] = {
    val result = new java.util.ArrayList[String](own.asJava)
    if (inherited != null) result.addAll(fallback(inherited))
    result
  }
  def include(regex: String): ChainedOptionsBuilder = { includes :+= regex; this }
  def exclude(regex: String): ChainedOptionsBuilder = { excludes :+= regex; this }
  def includeWarmup(regex: String): ChainedOptionsBuilder = { warmups :+= regex; this }
  def mode(value: Mode): ChainedOptionsBuilder = { modes.add(value); this }
  def getIncludes(): java.util.List[String] = merged(includes, _.getIncludes())
  def getExcludes(): java.util.List[String] = merged(excludes, _.getExcludes())
  def getWarmupIncludes(): java.util.List[String] = merged(warmups, _.getWarmupIncludes())
  def getBenchModes(): java.util.Collection[Mode] =
    if (modes.isEmpty && inherited != null) inherited.getBenchModes() else modes
  def param(name: String, values: String*): ChainedOptionsBuilder = {
    parameters.getOrElseUpdate(name, new java.util.ArrayList[String]()).addAll(values.asJava); this
  }
  def getParameter(name: String): Optional[java.util.Collection[String]] = parameters
    .get(name)
    .filter(v => !v.isEmpty())
    .map(v => Optional.of[java.util.Collection[String]](java.util.Collections.unmodifiableCollection(v)))
    .getOrElse(if (inherited == null) Optional.none[java.util.Collection[String]]() else inherited.getParameter(name))
  def threadGroups(groups: Int*): ChainedOptionsBuilder = {
    require(groups.nonEmpty && groups.forall(_ >= 0) && groups.exists(_ > 0), "Invalid thread group distribution")
    values("threadGroups") = groups.toArray; this
  }
  def getThreadGroups(): Optional[Array[Int]] = get("threadGroups", _.getThreadGroups())
  def jvmArgs(arguments: String*): ChainedOptionsBuilder = { values("jvmArgs") = arguments.toVector.asJava; this }
  def jvmArgsAppend(arguments: String*): ChainedOptionsBuilder = {
    values("jvmArgsAppend") = arguments.toVector.asJava; this
  }
  def jvmArgsPrepend(arguments: String*): ChainedOptionsBuilder = {
    values("jvmArgsPrepend") = arguments.toVector.asJava; this
  }
  def getJvmArgs(): Optional[java.util.Collection[String]] = get("jvmArgs", _.getJvmArgs())
  def getJvmArgsAppend(): Optional[java.util.Collection[String]] = get("jvmArgsAppend", _.getJvmArgsAppend())
  def getJvmArgsPrepend(): Optional[java.util.Collection[String]] = get("jvmArgsPrepend", _.getJvmArgsPrepend())
  def detectJvmArgs(): ChainedOptionsBuilder = jvmArgs()
  def warmupIterations(value: Int): ChainedOptionsBuilder = {
    require(value >= 0, "Invalid warmupIterations")
    values("warmupIterations") = value; this
  }
  def getWarmupIterations(): Optional[java.lang.Integer] = get("warmupIterations", _.getWarmupIterations())
  def measurementIterations(value: Int): ChainedOptionsBuilder = {
    require(value > 0, "Invalid measurementIterations")
    values("measurementIterations") = value; this
  }
  def getMeasurementIterations(): Optional[java.lang.Integer] =
    get("measurementIterations", _.getMeasurementIterations())
  def warmupBatchSize(value: Int): ChainedOptionsBuilder = {
    require(value > 0, "Invalid warmupBatchSize")
    values("warmupBatchSize") = value; this
  }
  def getWarmupBatchSize(): Optional[java.lang.Integer] = get("warmupBatchSize", _.getWarmupBatchSize())
  def measurementBatchSize(value: Int): ChainedOptionsBuilder = {
    require(value > 0, "Invalid measurementBatchSize")
    values("measurementBatchSize") = value; this
  }
  def getMeasurementBatchSize(): Optional[java.lang.Integer] = get("measurementBatchSize", _.getMeasurementBatchSize())
  def forks(value: Int): ChainedOptionsBuilder = {
    require(value >= 0, "Invalid forks")
    values("forks") = value; this
  }
  def getForkCount(): Optional[java.lang.Integer] = get("forks", _.getForkCount())
  def warmupForks(value: Int): ChainedOptionsBuilder = {
    require(value >= 0, "Invalid warmupForks")
    values("warmupForks") = value; this
  }
  def getWarmupForkCount(): Optional[java.lang.Integer] = get("warmupForks", _.getWarmupForkCount())
  def threads(value: Int): ChainedOptionsBuilder = {
    require(value > 0 || value == -1 || value == -2, "Invalid threads")
    values("threads") = value; this
  }
  def getThreads(): Optional[java.lang.Integer] = get("threads", _.getThreads())
  def operationsPerInvocation(value: Int): ChainedOptionsBuilder = {
    require(value > 0, "Invalid operationsPerInvocation")
    values("operationsPerInvocation") = value; this
  }
  def getOperationsPerInvocation(): Optional[java.lang.Integer] =
    get("operationsPerInvocation", _.getOperationsPerInvocation())
  def warmupTime(value: TimeValue): ChainedOptionsBuilder = {
    require(value != null, "Invalid warmupTime")
    values("warmupTime") = value; this
  }
  def getWarmupTime(): Optional[TimeValue] = get("warmupTime", _.getWarmupTime())
  def measurementTime(value: TimeValue): ChainedOptionsBuilder = {
    require(value != null, "Invalid measurementTime")
    values("measurementTime") = value; this
  }
  def getMeasurementTime(): Optional[TimeValue] = get("measurementTime", _.getMeasurementTime())
  def timeout(value: TimeValue): ChainedOptionsBuilder = {
    require(value != null, "Invalid timeout")
    values("timeout") = value; this
  }
  def getTimeout(): Optional[TimeValue] = get("timeout", _.getTimeout())
  def timeUnit(value: TimeUnit): ChainedOptionsBuilder = {
    require(value != null, "Invalid timeUnit")
    values("timeUnit") = value; this
  }
  def getTimeUnit(): Optional[TimeUnit] = get("timeUnit", _.getTimeUnit())
  def shouldDoGC(value: Boolean): ChainedOptionsBuilder = {
    require(true, "Invalid shouldDoGC")
    values("shouldDoGC") = value; this
  }
  def shouldDoGC(): Optional[java.lang.Boolean] = get("shouldDoGC", _.shouldDoGC())
  def syncIterations(value: Boolean): ChainedOptionsBuilder = {
    require(true, "Invalid syncIterations")
    values("syncIterations") = value; this
  }
  def shouldSyncIterations(): Optional[java.lang.Boolean] = get("syncIterations", _.shouldSyncIterations())
  def shouldFailOnError(value: Boolean): ChainedOptionsBuilder = {
    require(true, "Invalid shouldFailOnError")
    values("shouldFailOnError") = value; this
  }
  def shouldFailOnError(): Optional[java.lang.Boolean] = get("shouldFailOnError", _.shouldFailOnError())
  def verbosity(value: VerboseMode): ChainedOptionsBuilder = {
    require(value != null, "Invalid verbosity")
    values("verbosity") = value; this
  }
  def verbosity(): Optional[VerboseMode] = get("verbosity", _.verbosity())
  def warmupMode(value: WarmupMode): ChainedOptionsBuilder = {
    require(value != null, "Invalid warmupMode")
    values("warmupMode") = value; this
  }
  def getWarmupMode(): Optional[WarmupMode] = get("warmupMode", _.getWarmupMode())
  def output(value: String): ChainedOptionsBuilder = {
    require(value != null, "Invalid output")
    values("output") = value; this
  }
  def getOutput(): Optional[String] = get("output", _.getOutput())
  def result(value: String): ChainedOptionsBuilder = {
    require(value != null, "Invalid result")
    values("result") = value; this
  }
  def getResult(): Optional[String] = get("result", _.getResult())
  def resultFormat(value: ResultFormatType): ChainedOptionsBuilder = {
    require(value != null, "Invalid resultFormat")
    values("resultFormat") = value; this
  }
  def getResultFormat(): Optional[ResultFormatType] = get("resultFormat", _.getResultFormat())
  def jvm(value: String): ChainedOptionsBuilder = {
    require(value != null, "Invalid jvm")
    values("jvm") = value; this
  }
  def getJvm(): Optional[String] = get("jvm", _.getJvm())
}
