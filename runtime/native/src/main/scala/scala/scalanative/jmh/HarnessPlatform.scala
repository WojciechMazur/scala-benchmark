package scala.scalanative.jmh

/** Compile-time view of this backend, read by the harness macro. */
object HarnessPlatform:
  /** `@Platforms` values for which this backend generates harness code. */
  val names: Set[String] = Set("Native")

  /** Avoids a parameter-specialized copy of the outer harness boundary. */
  type boundary = scala.scalanative.annotation.nospecialize
