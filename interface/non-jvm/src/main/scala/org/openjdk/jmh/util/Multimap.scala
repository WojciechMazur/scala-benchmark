/*
 * Copyright (c) 2005, 2013, Oracle and/or its affiliates. All rights reserved.
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

import scala.jdk.CollectionConverters.*

trait Multimap[K, V] {
  def put(key: K, value: V): Unit
  def putAll(key: K, values: java.util.Collection[V]): Unit
  def get(key: K): java.util.Collection[V]
  def keys(): java.util.Collection[K]
  def values(): java.util.Collection[V]
  def entrySet(): java.util.Collection[java.util.Map.Entry[K, java.util.Collection[V]]]
  def merge(other: Multimap[K, V]): Unit
  def clear(): Unit
  def remove(key: K): Unit
  def isEmpty(): Boolean
}
class DelegatingMultimap[K, V](protected val map: java.util.Map[K, java.util.Collection[V]])
    extends Multimap[K, V]
    with Serializable {
  protected def createValueCollection(): java.util.Collection[V] = new java.util.ArrayList[V]()
  def put(key: K, value: V): Unit = {
    if (!map.containsKey(key)) map.put(key, createValueCollection())
    map.get(key).add(value)
  }
  def putAll(key: K, values: java.util.Collection[V]): Unit = {
    if (!map.containsKey(key)) map.put(key, createValueCollection())
    map.get(key).addAll(values)
  }
  def get(key: K): java.util.Collection[V] =
    if (map.containsKey(key)) java.util.Collections.unmodifiableCollection(map.get(key))
    else java.util.Collections.emptyList[V]()
  def keys(): java.util.Collection[K] = map.keySet()
  def values(): java.util.Collection[V] = {
    val result = createValueCollection()
    map.values().asScala.foreach(result.addAll)
    result
  }
  def entrySet(): java.util.Collection[java.util.Map.Entry[K, java.util.Collection[V]]] = map.entrySet()
  def merge(other: Multimap[K, V]): Unit = other.keys().asScala.foreach(key => putAll(key, other.get(key)))
  def clear(): Unit = map.clear()
  def remove(key: K): Unit = { map.remove(key); () }
  def isEmpty(): Boolean = map.isEmpty()
  override def equals(other: Any): Boolean = other match {
    case value: DelegatingMultimap[?, ?] => getClass == value.getClass && map == value.map
    case _                               => false
  }
  override def hashCode(): Int = map.hashCode()
  override def toString(): String = map.toString()
}
class HashMultimap[K, V] extends DelegatingMultimap[K, V](new java.util.HashMap[K, java.util.Collection[V]]())
class TreeMultimap[K, V] extends DelegatingMultimap[K, V](new java.util.TreeMap[K, java.util.Collection[V]]())

class HashsetMultimap[K, V] extends DelegatingMultimap[K, V](new java.util.HashMap[K, java.util.Collection[V]]) {
  override protected def createValueCollection(): java.util.Collection[V] = new java.util.HashSet[V]
}
