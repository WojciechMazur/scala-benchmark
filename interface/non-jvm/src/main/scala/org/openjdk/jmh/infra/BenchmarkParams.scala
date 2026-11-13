package org.openjdk.jmh.infra

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.runner.WorkloadParams
import org.openjdk.jmh.runner.options.TimeValue

class BenchmarkParams(
    private val benchmark: String,
    private val generatedTarget: String,
    private val synchIterations: Boolean,
    private val threads: Int,
    private val threadGroups: Array[Int],
    private val threadGroupLabels: java.util.Collection[String],
    private val forks: Int,
    private val warmupForks: Int,
    private val warmup: IterationParams,
    private val measurement: IterationParams,
    private val mode: Mode,
    private val params: WorkloadParams,
    private val timeUnit: TimeUnit,
    private val opsPerInvocation: Int,
    private val jvm: String,
    private val jvmArgs: java.util.Collection[String],
    private val jdkVersion: String,
    private val vmName: String,
    private val vmVersion: String,
    private val jmhVersion: String,
    private val timeout: TimeValue
) extends Comparable[BenchmarkParams]
    with Serializable:
  def getBenchmark(): String = benchmark
  def generatedBenchmark(): String = generatedTarget
  def shouldSynchIterations(): Boolean = synchIterations
  def getThreads(): Int = threads
  def getThreadGroups(): Array[Int] = threadGroups.clone()
  def getThreadGroupLabels(): java.util.Collection[String] = threadGroupLabels
  def getForks(): Int = forks
  def getWarmupForks(): Int = warmupForks
  def getWarmup(): IterationParams = warmup
  def getMeasurement(): IterationParams = measurement
  def getMode(): Mode = mode
  def getParamsKeys(): java.util.Collection[String] = params.keys()
  def getParam(key: String): String = if params == null then null else params.get(key)
  def getTimeUnit(): TimeUnit = timeUnit
  def getOpsPerInvocation(): Int = opsPerInvocation
  def getJvm(): String = jvm
  def getJvmArgs(): java.util.Collection[String] = java.util.Collections.unmodifiableCollection(jvmArgs)
  def getJdkVersion(): String = jdkVersion
  def getVmName(): String = vmName
  def getVmVersion(): String = vmVersion
  def getJmhVersion(): String = jmhVersion
  def getTimeout(): TimeValue = timeout

  def id(): String =
    import scala.jdk.CollectionConverters.*
    def escaped(value: String): String = scala.benchmark.internal.Ascii.formEncode(value)
    escaped(benchmark) + "-" + mode.toString +
      params
        .keys()
        .asScala
        .map(k => "-" + escaped(k) + "-" + escaped(params.get(k)))
        .mkString

  def compareTo(that: BenchmarkParams): Int =
    val byMode = mode.compareTo(that.mode)
    if byMode != 0 then byMode
    else
      val byName = benchmark.compareTo(that.benchmark)
      if byName != 0 then byName
      else if params == null || that.params == null then 0
      else params.compareTo(that.params)

  override def equals(other: Any): Boolean = other match
    case that: BenchmarkParams =>
      getClass == that.getClass && benchmark == that.benchmark && mode == that.mode && params == that.params
    case _ => false

  override def hashCode(): Int = 31 * (31 * benchmark.hashCode() + mode.hashCode()) + params.hashCode()

end BenchmarkParams
