package scala.benchmark.internal

/** Preserve JMH/Java extrema semantics even on hosts whose fmin/fmax discard a zero's sign. */
object StatisticsNumbers {
  def minimum(a: Double, b: Double): Double = {
    val left = java.lang.Double.doubleToRawLongBits(a)
    val right = java.lang.Double.doubleToRawLongBits(b)
    if ((left & Long.MaxValue) == 0 && (right & Long.MaxValue) == 0)
      java.lang.Double.longBitsToDouble(left | right)
    else if (a.isNaN || b.isNaN) Double.NaN
    else if (a < b) a
    else b
  }
  def maximum(a: Double, b: Double): Double = {
    val left = java.lang.Double.doubleToRawLongBits(a)
    val right = java.lang.Double.doubleToRawLongBits(b)
    if ((left & Long.MaxValue) == 0 && (right & Long.MaxValue) == 0)
      java.lang.Double.longBitsToDouble(left & right)
    else if (a.isNaN || b.isNaN) Double.NaN
    else if (a > b) a
    else b
  }
}
