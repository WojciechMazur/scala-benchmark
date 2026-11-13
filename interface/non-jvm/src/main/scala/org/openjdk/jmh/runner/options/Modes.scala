package org.openjdk.jmh.runner.options

enum VerboseMode extends java.lang.Enum[VerboseMode]:
  case SILENT, NORMAL, EXTRA
  def equalsOrHigherThan(other: VerboseMode): Boolean = ordinal >= other.ordinal

enum WarmupMode extends java.lang.Enum[WarmupMode]:
  case INDI, BULK, BULK_INDI
  def isBulk(): Boolean = this != INDI
  def isIndi(): Boolean = this != BULK
  def isBulkWarmup(): Boolean = this != INDI
  def isIndiWarmup(): Boolean = this != BULK

class CommandLineOptionException(message: String, cause: Throwable = null) extends Exception(message, cause)

object VerboseMode:

  @scala.annotation.targetName("javaValues")
  def values(): Array[VerboseMode] = Array(SILENT, NORMAL, EXTRA)

object WarmupMode:

  @scala.annotation.targetName("javaValues")
  def values(): Array[WarmupMode] = Array(INDI, BULK, BULK_INDI)
