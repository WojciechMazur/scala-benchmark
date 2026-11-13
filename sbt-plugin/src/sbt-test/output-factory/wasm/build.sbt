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
    ).getParentFile / "test/fixtures/output-factory",
    Compile / sources := (Compile / sources).value.filter(f =>
      f.getName == "OutputFactoryBenchmark.scala" || (f.getName == "Transcripts.scala" && f.getParentFile.getName == "js")
    )
  )
  .settings(OutputFactoryChecks.settings("wasm"))
