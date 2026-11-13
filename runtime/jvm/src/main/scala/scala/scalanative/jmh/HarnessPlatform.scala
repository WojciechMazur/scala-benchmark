package scala.scalanative.jmh

/** The JVM runtime only hosts deterministic harness tests, which exercise the Native code path. */
object HarnessPlatform:
  val names: Set[String] = Set("Native")

  final class boundary extends scala.annotation.StaticAnnotation
