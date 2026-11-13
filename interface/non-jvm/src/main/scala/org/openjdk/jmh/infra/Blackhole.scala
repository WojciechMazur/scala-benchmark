package org.openjdk.jmh.infra

import scala.benchmark.internal.BlackholeSink

/** Source-compatible consumption API. The Native backend is an observable C compiler barrier, rather than HotSpot's
  * compiler blackhole intrinsic.
  */
final class Blackhole(challengeResponse: String):

  require(
    challengeResponse == Blackhole.Challenge,
    "Blackholes must be injected by the harness"
  )

  def consume(value: Boolean): Unit = BlackholeSink.consume(if value then 1L else 0L)
  def consume(value: Byte): Unit = BlackholeSink.consume(value.toLong)
  def consume(value: Short): Unit = BlackholeSink.consume(value.toLong)
  def consume(value: Char): Unit = BlackholeSink.consume(value.toLong)
  def consume(value: Int): Unit = BlackholeSink.consume(value.toLong)
  def consume(value: Long): Unit = BlackholeSink.consume(value)

  def consume(value: Float): Unit =
    BlackholeSink.consume(java.lang.Float.floatToRawIntBits(value).toLong)

  def consume(value: Double): Unit =
    BlackholeSink.consume(java.lang.Double.doubleToRawLongBits(value))

  def consume(value: AnyRef): Unit = BlackholeSink.consumeReference(value)

  def evaporate(challengeResponse: String): Unit =
    require(challengeResponse == Blackhole.Challenge)

object Blackhole:

  final val Challenge =
    "Today's password is swordfish. I understand instantiating Blackholes directly is dangerous."

  def consumeCPU(tokens: Long): Unit =
    var value = 0x123456789abcdefL
    var i = 0L
    while i < tokens do
      value = (value * 0x5deece66dL + 0xbL) ^ (value >>> 17)
      i += 1
    BlackholeSink.consume(value)
