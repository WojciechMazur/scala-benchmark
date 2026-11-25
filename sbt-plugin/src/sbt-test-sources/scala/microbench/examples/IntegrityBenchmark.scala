package microbench.examples

import microbench.*

class IntegrityValue(val value: Long)

// `@noinline` keeps every workload inspectable as a function of its own in the optimized output.
// `@CompilerControl` only keeps the benchmark call out of the measured loop.
// Likewise `@inline` asks for a workload to be part of the measured loop.

/** Return values have no side effects: consuming them must retain the computation/allocation. */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1, batchSize = 7)
@Fork(0)
class IntegrityBenchmark:
  @Param(Array("41")) var seed: Long = 0L
  private var units = 0

  // Keep this value class's payload observable without adding payload reads to the measured loop.
  @noinline
  def readPayload(value: IntegrityValue): Long = value.value

  @Setup(Level.Trial) def verifyPayload(): Unit = assert(readPayload(new IntegrityValue(seed)) == seed)

  @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE) @noinline
  def primitiveBoolean(): Boolean = seed > 0

  @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE) @noinline
  def primitiveByte(): Byte = (seed + 1).toByte

  @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE) @noinline
  def primitiveShort(): Short = (seed + 2).toShort

  @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE) @noinline
  def primitiveChar(): Char = (seed + 3).toChar

  @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE) @noinline
  def primitiveInt(): Int = (seed * seed + 17).toInt

  @Benchmark @CompilerControl(CompilerControl.Mode.INLINE) @inline
  def inlinedInt(): Int = (seed * seed + 37).toInt

  @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE) @noinline
  def primitiveLong(): Long = seed * seed * seed + 19

  @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE) @noinline
  def primitiveFloat(): Float = seed.toFloat + 0.5f

  @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE) @noinline
  def primitiveDouble(): Double = seed.toDouble + 0.25

  @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE) @noinline
  def returnedAllocation(): IntegrityValue = new IntegrityValue(seed * seed + 23)

  @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE) @noinline
  def explicitBlackhole(hole: Blackhole): Unit =
    hole.consume(seed + 29)
    hole.consume(new IntegrityValue(seed + 31))

  @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE) @noinline
  def observableUnit(): Unit = units += 1

  @TearDown(Level.Trial) def verify(params: BenchmarkParams): Unit =
    assert(units == (if params.getBenchmark().endsWith(".observableUnit") then 7 else 0))

end IntegrityBenchmark
