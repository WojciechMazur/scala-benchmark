import scala.scalanative.build.LTO
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport.nativeConfig

// Apple ld needs these vendored libunwind bitcode roots for thin LTO with Scala Native 0.5.12.
val thinLtoRoots =
  if (sys.props("os.name").startsWith("Mac") && sys.env.get("SCALANATIVE_LTO").contains("thin"))
    Seq("get_proc_name", "regname", "iterate_dwarf_unwind_cache", "is_fpreg", "get_fpreg", "set_fpreg")
      .map("-Wl,-u,___unw_" + _)
  else Nil

lazy val benchmarks = FixtureBuild.matrix("native", "js", "wasm") { platform =>
  val optimized =
    if (platform == "native")
      Seq(
        nativeConfig ~= (_.withDump(true)
          // Scala Native 0.5.12 eh.cpp needs this explicit header with recent macOS SDKs.
          .withCppOptions(_ ++ Seq("-include", "cstdlib"))
          .withLinkingOptions(_ ++ thinLtoRoots))
      )
    else FixtureBuild.nodeImport(IntegrityChecks.observeHost)
  optimized ++ FixtureBuild.only("IntegrityBenchmark.scala") ++ IntegrityChecks.settings(platform)
}

lazy val root = project.in(file(".")).settings(FixtureBuild.commands)
