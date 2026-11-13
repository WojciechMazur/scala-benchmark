package scala.benchmark.internal

/** Locale-free protocol names also work on JS/Wasm, which do not provide Locale. */
object Ascii {
  def lower(value: String): String = value.map(c => if (c >= 'A' && c <= 'Z') (c + 32).toChar else c)
  def upper(value: String): String = value.map(c => if (c >= 'a' && c <= 'z') (c - 32).toChar else c)
  def formEncode(value: String): String = {
    val hex = "0123456789ABCDEF"
    val result = new java.lang.StringBuilder
    value.getBytes("UTF-8").foreach { byte =>
      val c = byte & 255
      if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "-_.*".contains(c.toChar))
        result.append(c.toChar)
      else if (c == ' ') result.append('+')
      else { result.append('%'); result.append(hex.charAt(c >>> 4)); result.append(hex.charAt(c & 15)) }
    }
    result.toString
  }
}
