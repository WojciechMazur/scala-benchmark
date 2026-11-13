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
