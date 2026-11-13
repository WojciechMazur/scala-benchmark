package scala.benchmark

/** Execution targets. JavaScript and WebAssembly are distinct targets. */
enum Platform {
  case JVM, Native, JS, Wasm
}

/** Restricts benchmark execution, while leaving ordinary Scala typechecking intact. Class, inherited and method
  * restrictions are intersected.
  */
final class Platforms(val value: Array[Platform], val reason: String = "") extends scala.annotation.StaticAnnotation
