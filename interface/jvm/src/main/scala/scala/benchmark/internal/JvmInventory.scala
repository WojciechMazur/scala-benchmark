package scala.benchmark.internal

/** Platform eligibility of the project's benchmarks, generated at compile time by the sbt plugin. */
object JvmInventory:

  /** Empty when the project has no generated inventory, for example a pure Java benchmark project. */
  def load(loader: ClassLoader): Vector[InventoryEntry] =
    try
      Class
        .forName(InventoryProvider.GeneratedName + "$", true, loader)
        .getField("MODULE$")
        .get(null)
        .asInstanceOf[InventoryProvider]
        .entries
        .toVector
    catch case _: ClassNotFoundException => Vector.empty
