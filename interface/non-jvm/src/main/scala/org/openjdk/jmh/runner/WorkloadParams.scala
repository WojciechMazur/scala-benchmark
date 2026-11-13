package org.openjdk.jmh.runner

import scala.jdk.CollectionConverters.*

class WorkloadParams extends Comparable[WorkloadParams] with Serializable:
  private val values = scala.collection.mutable.TreeMap.empty[String, (String, Int)]
  def put(key: String, value: String, order: Int): Unit = values(key) = (value, order)
  def get(key: String): String = values.get(key).map(_._1).orNull
  def containsKey(key: String): Boolean = values.contains(key)
  def keys(): java.util.Collection[String] = values.keys.toVector.asJava
  def isEmpty(): Boolean = values.isEmpty

  def copy(): WorkloadParams =
    val result = new WorkloadParams
    values.foreach { case (k, (v, n)) => result.put(k, v, n) }
    result

  def compareTo(that: WorkloadParams): Int =
    if values.keySet != that.values.keySet then
      throw new IllegalStateException("Comparing actual params with different key sets.")
    values.iterator
      .map { case (key, (_, order)) => Integer.compare(order, that.values(key)._2) }
      .find(_ != 0)
      .getOrElse(0)

  override def equals(other: Any): Boolean = other match
    case that: WorkloadParams =>
      getClass == that.getClass &&
      values.view.mapValues(_._1).toMap == that.values.view.mapValues(_._1).toMap
    case _ => false

  override def hashCode(): Int = values.iterator.map { case (k, (v, _)) =>
    Option(k).fold(0)(_.hashCode()) ^ Option(v).fold(0)(_.hashCode())
  }.sum

  override def toString(): String =
    values.iterator.map { case (key, (value, _)) => s"$key=$value" }.mkString("{", ", ", "}")

end WorkloadParams
