package scala.benchmark.internal

import java.io.StringReader
import java.util.Properties
import scala.benchmark.Platform

final case class InventoryEntry(name: String, eligibility: Eligibility)

object InventoryEntry {
  def parse(text: String): InventoryEntry = {
    val properties = new Properties
    properties.load(new StringReader(text))
    val platforms = Option(properties.getProperty("platforms"))
      .map(_.split(",").toVector.map(Platform.valueOf))
      .getOrElse(Platform.values.toVector)
    val count = properties.getProperty("platformReasons", "0").toInt
    InventoryEntry(
      properties.getProperty("name"),
      Eligibility(platforms, Vector.tabulate(count)(i => properties.getProperty(s"platformReason.$i")))
    )
  }
}
