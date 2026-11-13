package org.openjdk.jmh.runner.options

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.results.format.ResultFormatType
import org.openjdk.jmh.util.Optional
import scala.jdk.CollectionConverters.*

trait Options extends Serializable {
  def getWarmupIterations(): Optional[java.lang.Integer]
  def getMeasurementIterations(): Optional[java.lang.Integer]
  def getWarmupBatchSize(): Optional[java.lang.Integer]
  def getMeasurementBatchSize(): Optional[java.lang.Integer]
  def getForkCount(): Optional[java.lang.Integer]
  def getWarmupForkCount(): Optional[java.lang.Integer]
  def getThreads(): Optional[java.lang.Integer]
  def getOperationsPerInvocation(): Optional[java.lang.Integer]
  def getWarmupTime(): Optional[TimeValue]
  def getMeasurementTime(): Optional[TimeValue]
  def getTimeout(): Optional[TimeValue]
  def getTimeUnit(): Optional[TimeUnit]
  def shouldDoGC(): Optional[java.lang.Boolean]
  def shouldSyncIterations(): Optional[java.lang.Boolean]
  def shouldFailOnError(): Optional[java.lang.Boolean]
  def verbosity(): Optional[VerboseMode]
  def getWarmupMode(): Optional[WarmupMode]
  def getOutput(): Optional[String]
  def getResult(): Optional[String]
  def getResultFormat(): Optional[ResultFormatType]
  def getJvm(): Optional[String]
  def getIncludes(): java.util.List[String]
  def getExcludes(): java.util.List[String]
  def getWarmupIncludes(): java.util.List[String]
  def getBenchModes(): java.util.Collection[Mode]
  def getParameter(name: String): Optional[java.util.Collection[String]]
  def getThreadGroups(): Optional[Array[Int]]
  def getJvmArgs(): Optional[java.util.Collection[String]]
  def getJvmArgsAppend(): Optional[java.util.Collection[String]]
  def getJvmArgsPrepend(): Optional[java.util.Collection[String]]
}

trait ChainedOptionsBuilder {
  def build(): Options
  def parent(options: Options): ChainedOptionsBuilder
  def include(regex: String): ChainedOptionsBuilder
  def exclude(regex: String): ChainedOptionsBuilder
  def includeWarmup(regex: String): ChainedOptionsBuilder
  def mode(mode: Mode): ChainedOptionsBuilder
  def param(name: String, values: String*): ChainedOptionsBuilder
  def threadGroups(groups: Int*): ChainedOptionsBuilder
  def jvmArgs(arguments: String*): ChainedOptionsBuilder
  def jvmArgsAppend(arguments: String*): ChainedOptionsBuilder
  def jvmArgsPrepend(arguments: String*): ChainedOptionsBuilder
  def detectJvmArgs(): ChainedOptionsBuilder
  def warmupIterations(value: Int): ChainedOptionsBuilder
  def measurementIterations(value: Int): ChainedOptionsBuilder
  def warmupBatchSize(value: Int): ChainedOptionsBuilder
  def measurementBatchSize(value: Int): ChainedOptionsBuilder
  def forks(value: Int): ChainedOptionsBuilder
  def warmupForks(value: Int): ChainedOptionsBuilder
  def threads(value: Int): ChainedOptionsBuilder
  def operationsPerInvocation(value: Int): ChainedOptionsBuilder
  def warmupTime(value: TimeValue): ChainedOptionsBuilder
  def measurementTime(value: TimeValue): ChainedOptionsBuilder
  def timeout(value: TimeValue): ChainedOptionsBuilder
  def timeUnit(value: TimeUnit): ChainedOptionsBuilder
  def shouldDoGC(value: Boolean): ChainedOptionsBuilder
  def syncIterations(value: Boolean): ChainedOptionsBuilder
  def shouldFailOnError(value: Boolean): ChainedOptionsBuilder
  def verbosity(value: VerboseMode): ChainedOptionsBuilder
  def warmupMode(value: WarmupMode): ChainedOptionsBuilder
  def output(value: String): ChainedOptionsBuilder
  def result(value: String): ChainedOptionsBuilder
  def resultFormat(value: ResultFormatType): ChainedOptionsBuilder
  def jvm(value: String): ChainedOptionsBuilder
}
