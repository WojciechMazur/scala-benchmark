lazy val benchmarks = FixtureBuild
  .matrix("native")
  .settings(
    Compile / nativeConfig ~= (_.withDump(true)
      // Scala Native 0.5.12 eh.cpp needs this explicit header with recent macOS SDKs.
      .withCppOptions(_ ++ Seq("-include", "cstdlib"))
      .withLTO(scala.scalanative.build.LTO.thin)
      // Apple ld needs these vendored libunwind bitcode roots with Native 0.5.12.
      .withLinkingOptions(options =>
        options ++ (if (sys.props("os.name").startsWith("Mac"))
                      Seq(
                        "get_proc_name",
                        "regname",
                        "iterate_dwarf_unwind_cache",
                        "is_fpreg",
                        "get_fpreg",
                        "set_fpreg"
                      )
                        .map(name => "-Wl,-u,___unw_" + name)
                    else Nil)
      ))
  )
  .settings(Compile / sources := (Compile / sources).value.filter(_.getName == "IntegrityBenchmark.scala"))
lazy val root = project
  .in(file("."))
  .settings(IntegrityChecks.settings(benchmarks.native(sys.props("benchmark.scala.version")), "native"))
