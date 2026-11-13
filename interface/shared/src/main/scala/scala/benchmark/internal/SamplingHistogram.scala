package scala.benchmark.internal

/** JMH 1.37 SampleBuffer's 10-bit quantized histogram and halving semantics. */
class SamplingHistogram:
  private val buckets = new Array[Array[Long]](54)

  def add(value: Long): Unit =
    require(value >= 0, "Negative clock interval")
    val bucket = math.max(0, 54 - java.lang.Long.numberOfLeadingZeros(value))
    if buckets(bucket) == null then buckets(bucket) = new Array[Long](1024)
    buckets(bucket)((value >>> bucket).toInt) += 1

  def half(): Unit = buckets.foreach { bucket =>
    if bucket != null then
      var i = 0
      while i < bucket.length do
        val n = bucket(i) / 2
        if n != 0 then bucket(i) = n
        i += 1
  }

  def entries: Vector[(Long, Long)] = buckets.iterator.zipWithIndex.flatMap { case (bucket, shift) =>
    if bucket == null then Iterator.empty
    else
      bucket.iterator.zipWithIndex.collect {
        case (count, n) if count != 0 => (n.toLong << shift, count)
      }
  }.toVector

  def merge(other: SamplingHistogram): Unit = other.entries.foreach { case (value, count) =>
    val bucket = math.max(0, 54 - java.lang.Long.numberOfLeadingZeros(value))
    if buckets(bucket) == null then buckets(bucket) = new Array[Long](1024)
    buckets(bucket)((value >>> bucket).toInt) += count
  }

  def addCount(value: Long, count: Long): Unit =
    require(count > 0)
    add(value)
    val bucket = math.max(0, 54 - java.lang.Long.numberOfLeadingZeros(value))
    buckets(bucket)((value >>> bucket).toInt) += count - 1

end SamplingHistogram
