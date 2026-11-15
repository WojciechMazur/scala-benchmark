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

final class Optional[T] private (private val value: Option[T]) extends Serializable:
  def hasValue(): Boolean = value.nonEmpty
  def get(): T = value.getOrElse(throw new IllegalStateException("Optional is null"))
  def orElse(fallback: T): T = value.getOrElse(fallback)
  def orElseGet(fallback: java.util.function.Supplier[T]): T = value.getOrElse(fallback.get())
  def orAnother(fallback: Optional[T]): Optional[T] = if value.nonEmpty then this else fallback

  override def equals(other: Any): Boolean = other match
    case that: Optional[?] =>
      (value, that.value) match
        case (None, None)       => true
        case (Some(a), Some(b)) => java.util.Objects.equals(a.asInstanceOf[AnyRef], b.asInstanceOf[AnyRef])
        case _                  => false
    case _ => false

  override def hashCode(): Int = value.fold(0)(_.hashCode())
  override def toString(): String = value.fold("[]")(v => "[" + v + "]")

object Optional:
  def none[T](): Optional[T] = new Optional(None)

  def of[T](value: T): Optional[T] =
    require(value != null, "Optional value is null")
    new Optional(Some(value))

  def eitherOf[T](value: T): Optional[T] = new Optional(Option(value))
