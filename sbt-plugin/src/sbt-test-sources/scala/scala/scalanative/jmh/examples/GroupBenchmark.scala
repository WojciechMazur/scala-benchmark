package scala.scalanative.jmh.examples

import scala.benchmark.*
import java.util.concurrent.ConcurrentHashMap

@State(Scope.Thread)
class GroupThreadState {
  var worker = -1
  @Setup(Level.Trial) def setup(t: ThreadParams): Unit = worker = t.getThreadIndex()
}
@State(Scope.Group)
class GroupSharedState {
  var setups = 0
  @Setup(Level.Trial) def setup(): Unit = { setups += 1; assert(setups == 1) }
}
@State(Scope.Benchmark)
class GroupGlobalState {
  val groups = new ConcurrentHashMap[Integer, GroupSharedState]
  val workers = new ConcurrentHashMap[Integer, GroupThreadState]
  @TearDown(Level.Trial) def verify(): Unit = {
    assert(groups.size() == 2 && workers.size() == 6)
    assert(groups.get(0) ne groups.get(1))
  }
}

@Platforms(Array(Platform.JVM, Platform.Native), reason = "Requires concurrent group workers")
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 2)
@Threads(4)
@Fork(1)
class GroupBenchmark {
  private def check(
      t: ThreadParams,
      local: GroupThreadState,
      group: GroupSharedState,
      global: GroupGlobalState
  ): Int = {
    assert(t.getThreadCount() == 6 && t.getGroupCount() == 2 && t.getGroupThreadCount() == 3)
    assert(local.worker == t.getThreadIndex())
    assert(group.setups == 1)
    val prior = global.groups.putIfAbsent(t.getGroupIndex(), group)
    assert(prior == null || (prior eq group))
    val threadPrior = global.workers.putIfAbsent(t.getThreadIndex(), local)
    assert(threadPrior == null || (threadPrior eq local))
    t.getThreadIndex()
  }
  @Benchmark @Group("exchange") @GroupThreads(1)
  def read(t: ThreadParams, local: GroupThreadState, group: GroupSharedState, global: GroupGlobalState): Int = {
    assert(t.getSubgroupIndex() == 0 && t.getSubgroupThreadCount() == 1)
    check(t, local, group, global)
  }
  @Benchmark @Group("exchange") @GroupThreads(2)
  def write(t: ThreadParams, local: GroupThreadState, group: GroupSharedState, global: GroupGlobalState): Int = {
    assert(t.getSubgroupIndex() == 1 && t.getSubgroupThreadCount() == 2)
    check(t, local, group, global)
  }
}

@State(Scope.Group)
class SingleGroupState { var initialized = false; @Setup def setup(): Unit = initialized = true }

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(1)
class SingleWorkerGroupBenchmark {
  @Benchmark @Group("one") def execute(state: SingleGroupState): Boolean = {
    assert(state.initialized); state.initialized
  }
}

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(1)
class MultiWorkerGroupBenchmark {
  @Benchmark @Group("pair") def read(): Int = 1
  @Benchmark @Group("pair") def write(): Int = 2
}
