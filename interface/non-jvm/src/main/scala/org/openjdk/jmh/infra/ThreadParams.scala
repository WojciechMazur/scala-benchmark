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
package org.openjdk.jmh.infra

final class ThreadParams(
    private val index: Int,
    private val count: Int,
    private val groupIndex: Int,
    private val groupCount: Int,
    private val subgroupIndex: Int,
    private val subgroupCount: Int,
    private val groupThreadIndex: Int,
    private val groupThreadCount: Int,
    private val subgroupThreadIndex: Int,
    private val subgroupThreadCount: Int
) {
  def this(index: Int, count: Int) =
    this(index, count, index, count, 0, 1, 0, 1, 0, 1)
  def getThreadIndex(): Int = index
  def getThreadCount(): Int = count
  def getGroupIndex(): Int = groupIndex
  def getGroupCount(): Int = groupCount
  def getGroupThreadIndex(): Int = groupThreadIndex
  def getGroupThreadCount(): Int = groupThreadCount
  def getSubgroupIndex(): Int = subgroupIndex
  def getSubgroupCount(): Int = subgroupCount
  def getSubgroupThreadIndex(): Int = subgroupThreadIndex
  def getSubgroupThreadCount(): Int = subgroupThreadCount
}
