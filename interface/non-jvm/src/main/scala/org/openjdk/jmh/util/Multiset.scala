/*
 * Copyright (c) 2005, 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2014, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.jmh.util

trait Multiset[T] {
  def add(element: T): Unit
  def add(element: T, count: Long): Unit
  def count(element: T): Long
  def entrySet(): java.util.Collection[java.util.Map.Entry[T, java.lang.Long]]
  def isEmpty(): Boolean
  def size(): Long
  def keys(): java.util.Collection[T]
  def clear(): Unit
}
class DelegatingMultiset[T](protected val map: java.util.Map[T, java.lang.Long]) extends Multiset[T] with Serializable {
  private var total = 0L
  def add(element: T): Unit = add(element, 1L)
  def add(element: T, count: Long): Unit = {
    val previous = map.get(element)
    val current = (if (previous == null) 0L else previous.longValue()) + count
    total += count
    if (current != 0L) map.put(element, current) else map.remove(element)
    ()
  }
  def count(element: T): Long = { val value = map.get(element); if (value == null) 0L else value.longValue() }
  def entrySet(): java.util.Collection[java.util.Map.Entry[T, java.lang.Long]] = map.entrySet()
  def isEmpty(): Boolean = map.isEmpty()
  def size(): Long = total
  def keys(): java.util.Collection[T] = java.util.Collections.unmodifiableCollection(map.keySet())
  def clear(): Unit = { total = 0; map.clear() }
  override def equals(other: Any): Boolean = other match {
    case that: DelegatingMultiset[?] => getClass == that.getClass && total == that.total && map == that.map
    case _                           => false
  }
  override def hashCode(): Int = 31 * map.hashCode() + (total ^ (total >>> 32)).toInt
}
class HashMultiset[T] extends DelegatingMultiset[T](new java.util.HashMap[T, java.lang.Long])
class TreeMultiset[T <: Comparable[T]] extends DelegatingMultiset[T](new java.util.TreeMap[T, java.lang.Long])

class BoundedPriorityQueue[E](private val maxSize: Int, ordering: java.util.Comparator[? >: E])
    extends java.util.AbstractQueue[E]
    with Serializable {
  def this(maxSize: Int) = this(maxSize, null)
  private val comparator: java.util.Comparator[? >: E] =
    if (ordering == null) java.util.Collections.reverseOrder[E]() else java.util.Collections.reverseOrder(ordering)
  private val queue = new java.util.PriorityQueue[E](10, comparator)
  override def add(value: E): Boolean = offer(value)
  def offer(value: E): Boolean = {
    if (queue.size() >= maxSize) {
      if (comparator.compare(value, peek()) < 1) return false
      poll()
    }
    queue.offer(value)
  }
  def poll(): E = queue.poll()
  def peek(): E = queue.peek()
  def iterator(): java.util.Iterator[E] = queue.iterator()
  def size(): Int = queue.size()
}
class Multisets
object Multisets {
  def sortedDesc[T](set: Multiset[T]): java.util.List[T] = {
    val result = new java.util.ArrayList[T](set.keys())
    result.sort((a, b) => java.lang.Long.compare(set.count(b), set.count(a)))
    result
  }
  def countHighest[T](set: Multiset[T], top: Int): java.util.List[T] = {
    val queue = new BoundedPriorityQueue[java.util.Map.Entry[T, java.lang.Long]](
      top,
      (a, b) => java.lang.Long.compare(b.getValue.longValue(), a.getValue.longValue())
    )
    queue.addAll(set.entrySet())
    val result = new java.util.ArrayList[T]
    var entry = queue.poll()
    while (entry != null) { result.add(entry.getKey()); entry = queue.poll() }
    java.util.Collections.reverse(result)
    result
  }
}
