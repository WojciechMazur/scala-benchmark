package scala.benchmark.runtime

/** Harnesses and build information of the benchmark project, installed by its generated entry point. */
object Registry:
  private var suites = Map.empty[String, Suite]
  private var build = Vector.empty[(String, String)]

  def install(suites: Seq[(String, Suite)], buildInfo: Seq[(String, String)]): Unit =
    this.suites = suites.toMap
    this.build = buildInfo.toVector.sortBy(_._1)

  /** Benchmark class names, in a stable order. */
  def names: Vector[String] = suites.keys.toVector.sorted

  def suite(name: String): Option[Suite] = suites.get(name)

  def buildInfo: Vector[(String, String)] = build
