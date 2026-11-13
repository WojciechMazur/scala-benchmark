lazy val benchmarks = FixtureBuild
  .matrix("wasm")
  .settings(
    scalaJSLinkerConfig ~= (_.withESFeatures(
      _.withESVersion(org.scalajs.linker.interface.ESVersion.ES2022).withUseWebAssembly(true)
    ))
  )
  .settings(
    Compile / unmanagedSourceDirectories += file(
      sys.props("benchmark.scripted.support")
    ).getParentFile / "test/fixtures/formatters",
    Compile / sources := (Compile / sources).value.filter(_.getName == "FormatterBenchmark.scala")
  )
  .settings(ResultFormatChecks.settings("wasm"))
