package microbench.examples

import microbench.*

@AuxCounters(AuxCounters.Type.EVENTS)
@State(Scope.Thread)
class EventCounters:
  private var count = 0L
  @Setup(Level.Iteration) def reset(): Unit = count = 0L
  def events(): Long = count
  def increment(): Unit = count += 2L
  @TearDown(Level.Iteration) def finish(): Unit = count += 3L
  @TearDown(Level.Trial) def finishTrial(): Unit = count += 5L

@AuxCounters(AuxCounters.Type.OPERATIONS)
@State(Scope.Thread)
class OperationCounters:
  private var count = 0L
  @Setup(Level.Iteration) def reset(): Unit = count = 0L
  def operations(): Long = count
  def increment(): Unit = count += 1L

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 1, batchSize = 4)
@Measurement(iterations = 2, batchSize = 3)
@Fork(1)
class CounterBenchmark:

  @Benchmark def execute(events: EventCounters, operations: OperationCounters): Int =
    events.increment(); operations.increment(); 1
