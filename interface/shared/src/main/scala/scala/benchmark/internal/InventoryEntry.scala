package scala.benchmark.internal

/** A benchmark, or benchmark group, by its canonical name and the platforms it may run on. */
final case class InventoryEntry(name: String, eligibility: Eligibility)
