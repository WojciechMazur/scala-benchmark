package scala.benchmark.examples

import scala.benchmark.*

@State(Scope.Benchmark)
class LifecycleBase {
  var inheritedReady = false
  var inheritedClosed = false
  @Setup(Level.Trial) def inheritedStart(): Unit = { assert(!inheritedReady); inheritedReady = true }
  @TearDown(Level.Trial) def inheritedEnd(): Unit = { assert(inheritedReady); inheritedClosed = true }
}

@State(Scope.Benchmark)
class LifecycleRoot extends LifecycleBase {
  var starts = 0
  var ends = 0
  @Setup(Level.Trial) def start(): Unit = assert(inheritedReady)
  @Setup(Level.Iteration) def startIteration(): Unit = { assert(starts == ends); starts += 1 }
  @TearDown(Level.Iteration) def endIteration(): Unit = { assert(starts == ends + 1); ends += 1 }
  @TearDown(Level.Trial) def end(): Unit = { assert(inheritedClosed); assert(starts == 3 && ends == 3) }
}

@State(Scope.Benchmark)
class LifecycleLeft {
  var root: LifecycleRoot = null
  @Setup(Level.Trial) def start(r: LifecycleRoot): Unit = { assert(r.inheritedReady); root = r }
  @TearDown(Level.Trial) def end(r: LifecycleRoot): Unit = assert(root eq r)
}

@State(Scope.Benchmark)
class LifecycleRight {
  var root: LifecycleRoot = null
  @Setup(Level.Trial) def start(r: LifecycleRoot): Unit = { assert(r.inheritedReady); root = r }
  @TearDown(Level.Trial) def end(r: LifecycleRoot): Unit = assert(root eq r)
}

@State(Scope.Thread)
class LifecycleLocal {
  var left: LifecycleLeft = null
  var right: LifecycleRight = null
  var starts = 0
  var ends = 0
  @Setup(Level.Trial) def start(l: LifecycleLeft, r: LifecycleRight): Unit = {
    assert(l.root eq r.root); left = l; right = r
  }
  @Setup(Level.Invocation) def before(): Unit = { assert(starts == ends); starts += 1 }
  @TearDown(Level.Invocation) def after(): Unit = { assert(starts == ends + 1); ends += 1 }
  @TearDown(Level.Trial) def end(l: LifecycleLeft, r: LifecycleRight): Unit = {
    assert((l eq left) && (r eq right)); assert(starts == 9 && ends == 9)
  }
}

@State(Scope.Group)
class LifecycleGroup {
  var left: LifecycleLeft = null
  var right: LifecycleRight = null
  @Setup(Level.Trial) def start(l: LifecycleLeft, r: LifecycleRight): Unit = { left = l; right = r }
  @TearDown(Level.Trial) def end(l: LifecycleLeft, r: LifecycleRight): Unit = assert((l eq left) && (r eq right))
}

@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 1, batchSize = 3)
@Measurement(iterations = 2, batchSize = 3)
@Fork(1)
class SharedLifecycleBenchmark {
  @Benchmark def execute(local: LifecycleLocal, l: LifecycleLeft, r: LifecycleRight, root: LifecycleRoot): Int = {
    assert((local.left eq l) && (local.right eq r) && (l.root eq root) && (r.root eq root))
    1
  }
}

@Platforms(Array(Platform.JVM, Platform.Native), reason = "Requires concurrent group workers")
@BenchmarkMode(Array(Mode.SingleShotTime))
@Warmup(iterations = 1, batchSize = 3)
@Measurement(iterations = 2, batchSize = 3)
@Threads(4)
@Fork(1)
class SharedGroupLifecycleBenchmark {
  @Benchmark @Group("identity") @GroupThreads(1)
  def a(local: LifecycleLocal, group: LifecycleGroup, root: LifecycleRoot): Int = check(local, group, root)
  @Benchmark @Group("identity") @GroupThreads(1)
  def b(local: LifecycleLocal, group: LifecycleGroup, root: LifecycleRoot): Int = check(local, group, root)
  private def check(local: LifecycleLocal, group: LifecycleGroup, root: LifecycleRoot): Int = {
    assert((local.left eq group.left) && (local.right eq group.right))
    assert((group.left.root eq root) && (group.right.root eq root))
    1
  }
}
