package microbench.internal

import microbench.Platform
import org.junit.Assert.*
import org.junit.Test

class SelectionTest:
  case class Entry(name: String, eligibility: Eligibility)

  val entries = Vector(
    Entry("bench.A.run", Eligibility()),
    Entry("bench.B.run", Eligibility(Vector(Platform.Native), Vector("workers"))),
    Entry("bench.C.run", Eligibility(Vector(Platform.Wasm)))
  )

  def select(
      includes: Vector[String] = Vector.empty,
      excludes: Vector[String] = Vector.empty,
      platform: Platform = Platform.JS,
      list: Boolean = false
  ) =
    Selection(entries, includes, excludes, platform, list)(_.name, _.eligibility)

  @Test def aggregateAndRegexSkipUnsupported(): Unit =
    val all = select()
    assertEquals(Vector(entries.head), all.selected)
    assertEquals(entries.tail, all.skipped)
    assertEquals(Vector(entries(1)), select(Vector("bench.B.*")).skipped)
    assertTrue(select(Vector("bench.B.*")).selected.isEmpty)

  @Test def exactIsPreflightedAndExclusionsWin(): Unit =
    val error = assertThrows(classOf[IllegalArgumentException], () => select(Vector("bench.A.run", "bench.B.run")))
    assertTrue(error.getMessage.contains("bench.B.run is unavailable on JS"))
    assertTrue(error.getMessage.contains("workers"))
    assertEquals(Vector(entries.head), select(Vector("bench.A.run", "bench.B.run"), Vector("bench.B.*")).selected)

  @Test def listingDoesNotFailAndWasmIsDistinct(): Unit =
    assertEquals(Vector(entries(1)), select(Vector("bench.B.run"), list = true).selected)
    assertTrue(select(Vector("missing"), list = true).selected.isEmpty)
    assertEquals(Vector(entries.head, entries(2)), select(platform = Platform.Wasm).selected)

  @Test def noMatchesRetainsSelectionError(): Unit =
    assertThrows(classOf[IllegalArgumentException], () => select(Vector("missing")))

  @Test def canonicalNamesAreLiterals(): Unit =
    val unusual = Entry("bench.[A].run", Eligibility())
    assertEquals(
      Vector(unusual),
      Selection(Vector(unusual), Vector(unusual.name), Vector.empty, Platform.JVM)(_.name, _.eligibility).selected
    )

  @Test def portableConfigurationOverrides(): Unit =
    import microbench.runtime.{Options, Configuration, BenchmarkRunner}
    val options = Options.parse(Array("-opi", "13", "-si", "false", "-e", "first,second"))
    val config = options.configuration(Configuration(operationsPerInvocation = 7))
    assertEquals(13, config.operationsPerInvocation)
    assertFalse(config.synchIterations)
    assertEquals(Vector("first", "second"), options.excludes)
    assertThrows(
      classOf[IllegalArgumentException],
      () => Options.parse(Array("-opi", "0")).configuration(Configuration())
    )
    assertFalse(Options.parse(Array("-si", "invalid")).configuration(Configuration()).synchIterations)
    val ordered = Configuration(parameterOrder = Map("size" -> 2, "a.b" -> 3))
    assertEquals(
      ordered.parameterOrder,
      Options(overrides = Configuration.overrides(ordered)).configuration(Configuration()).parameterOrder
    )
    assertTrue(BenchmarkRunner.run(Options.parse(Array("-lrf"))).isEmpty)

end SelectionTest
