package scala.benchmark.sbtplugin

import sbt.{*, given}
import sbt.Keys.*
import sbt.complete.DefaultParsers.spaceDelimited
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.*
import pl.project13.scala.sbt.JmhPlugin
import pl.project13.scala.sbt.JmhPlugin.JmhKeys.*
import NativeBenchmarkPlugin.autoImport.*

/** Node hosts the optimized JS or Wasm module, with fresh processes for forks. */
object ScalaJSBenchmarkPlugin extends AutoPlugin {
  override def requires: Plugins = ScalaJSPlugin && JmhPlugin
  override def trigger: PluginTrigger = allRequirements

  object autoImport {
    val benchmarkNode = settingKey[String]("Node.js executable used for benchmarks")
    val benchmarkNodeOptions = settingKey[Seq[String]]("Node.js options inherited by benchmark forks")
  }
  import autoImport.*

  override def projectSettings: Seq[Setting[?]] = Seq(
    benchmarkUsePublishedArtifacts := true,
    benchmarkNode := "node",
    benchmarkNodeOptions := Nil,
    libraryDependencies ~= (_.filterNot(_.organization == "org.openjdk.jmh")),
    libraryDependencies ++= {
      if (!benchmarkUsePublishedArtifacts.value) Nil
      else {
        require(
          BuildInfo.supportedScalaVersions.contains(scalaVersion.value),
          "Unsupported benchmark compiler: " + scalaVersion.value
        )
        Seq(
          ("org.scala-native" %% "benchmark-runtime" % BuildInfo.version).platform(platform.value),
          compilerPlugin("org.scala-native" % "benchmark-plugin" % BuildInfo.version)
            .cross(CrossVersion.full)
            .platform(sbt.Platform.jvm)
        )
      }
    },
    Compile / unmanagedSourceDirectories ++= (Jmh / unmanagedSourceDirectories).value,
    Compile / mainClass := Some("scala.scalanative.jmh.Main"),
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    Compile / scalacOptions += "-P:scalanative-jmh:backend=js",
    // Inline runtime bodies are compiler inputs even when absent from user sources.
    Compile / scalacOptions += Def.uncached {
      val converter = fileConverter.value
      val digest = java.security.MessageDigest.getInstance("SHA-256")
      (Compile / dependencyClasspath).value
        .map(e => converter.toPath(e.data))
        .filter(p => p.getFileName.toString.startsWith("benchmark-runtime") && p.toString.endsWith(".jar"))
        .foreach(p => digest.update(java.nio.file.Files.readAllBytes(p)))
      "-P:scalanative-jmh:runtimeKey=" + digest.digest().map(b => f"${b & 0xff}%02x").mkString
    },
    Jmh / run / mainClass := (Compile / mainClass).value,
    Jmh / compile := Def.uncached { (Compile / compile).value },
    Jmh / sourceGenerators := Nil,
    Jmh / resourceGenerators := Nil,
    benchmarkInventory := Def.uncached {
      require((Jmh / version).value == "1.37", "The Scala.js backend supports JMH API 1.37")
      (Compile / compile).value
      NativeBenchmarkPlugin.removeOrphans((Compile / classDirectory).value.toPath)
      val converter = fileConverter.value
      val paths = (Compile / classDirectory).value.toPath +:
        (Compile / dependencyClasspath).value.map(e => converter.toPath(e.data))
      val entries = NativeBenchmarkPlugin.discover(paths)
      val manifest = (Compile / resourceManaged).value / "scala-benchmark/manifest.json"
      val wasm = scalaJSLinkerConfig.value.esFeatures.useWebAssembly
      val metadata = Map(
        "scalaVersion" -> scalaVersion.value,
        "scalaJSVersion" -> scalaJSVersion,
        "backend" -> (if (wasm) "wasm" else "js"),
        "optimization" -> "full"
      )
      val text = "{\"inventory\":" + entries.map(quote).mkString("[", ",", "]") +
        ",\"metadata\":" + metadata.toVector.sorted
          .map { case (k, v) => quote(k) + ":" + quote(v) }
          .mkString("{", ",", "}") + "}"
      if (!manifest.exists() || IO.read(manifest) != text) IO.write(manifest, text)
      Seq(manifest)
    },
    Jmh / JmhPlugin.generateJmhSourcesAndResources := Def.uncached { (Seq.empty[File], benchmarkInventory.value) },
    Jmh / run := {
      val arguments = spaceDelimited("<JMH option>").parsed
      val manifest = benchmarkInventory.value.head
      val report = (Compile / fullLinkJS).value.data
      val modules = report.publicModules.toVector
      require(modules.size == 1, "Benchmark linking must produce one public module")
      val module = (Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value / modules.head.jsFileName
      val launcher = target.value / "scala-benchmark/node-host.mjs"
      Seq("node-host.mjs", "progress-deadline.mjs", "worker-lifeline.mjs", "output-relay.mjs", "console-writer.mjs")
        .foreach { resource =>
          val stream = getClass.getResourceAsStream("/scala-benchmark/" + resource)
          require(stream != null, "Missing benchmark Node resource: " + resource)
          try IO.write(launcher.getParentFile / resource, IO.readStream(stream))
          finally stream.close()
        }
      val exposeGC = arguments.sliding(2).exists(pair => pair == Seq("-gc", "true"))
      val gcOptions = if (exposeGC && !benchmarkNodeOptions.value.contains("--expose-gc")) Seq("--expose-gc") else Nil
      val command = Seq(benchmarkNode.value) ++ benchmarkNodeOptions.value ++ gcOptions ++
        Seq(launcher.getAbsolutePath, module.getAbsolutePath, manifest.getAbsolutePath) ++ arguments
      val exit = scala.sys.process.Process(command, baseDirectory.value, (Jmh / run / envVars).value.toSeq*).!
      if (exit != 0) sys.error(s"Scala.js benchmark exited with $exit")
    }
  )

  private def quote(value: String): String = "\"" + value.flatMap {
    case '"'          => "\\\""
    case '\\'         => "\\\\"
    case '\n'         => "\\n"
    case '\r'         => "\\r"
    case '\t'         => "\\t"
    case c if c < ' ' => f"\\u${c.toInt}%04x"
    case c            => c.toString
  } + "\""
}
