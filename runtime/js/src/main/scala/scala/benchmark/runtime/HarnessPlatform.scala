package scala.benchmark.runtime

/** Compile-time view of this backend, read by the harness macro. */
object HarnessPlatform:
  /** `@Platforms` values for which this backend generates harness code. JS and Wasm share one compilation. */
  val names: Set[String] = Set("JS", "Wasm")

  final class boundary extends scala.annotation.StaticAnnotation
