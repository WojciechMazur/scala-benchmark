package scala.benchmark.sbtplugin

import sbt.{*, given}
import sbt.Keys.*
import sbt.complete.DefaultParsers.spaceDelimited
import pl.project13.scala.sbt.JmhPlugin.JmhKeys.*
import pl.project13.scala.sbt.JmhPlugin
import NativeBenchmarkPlugin.autoImport.benchmarkUsePublishedArtifacts

/** Keep upstream JMH execution, normalizing Scala facade annotations only. */
object JvmBenchmarkPlugin extends AutoPlugin {
  override def requires: Plugins = JmhPlugin
  override def trigger: PluginTrigger = allRequirements

  override def projectSettings: Seq[Setting[?]] = Seq(
    benchmarkUsePublishedArtifacts := true,
    Jmh / run / mainClass := Some("scala.benchmark.internal.JvmMain"),
    Jmh / JmhPlugin.generateJmhSourcesAndResources := Def.uncached {
      (Compile / compile).value
      val converter = fileConverter.value
      val bytecode = (Jmh / classDirectory).value.toPath
      NativeBenchmarkPlugin.removeOrphans(bytecode)
      val sources = (Jmh / sourceManaged).value
      val resources = (Jmh / resourceManaged).value
      val dependencies = (Jmh / dependencyClasspath).value.map(e => converter.toPath(e.data))
      val log = streams.value.log
      require(
        (Jmh / generatorType).value != "asm",
        "Platform eligibility currently requires the reflection JMH generator"
      )
      // Regenerate the complete inventory so removals and restriction edits cannot
      // leave stale BenchmarkList entries from an earlier generation.
      IO.delete(sources)
      IO.delete(resources)
      IO.createDirectory(sources)
      IO.createDirectory(resources)
      new ForkRun(ForkOptions())
        .run(
          "scala.benchmark.internal.JvmGenerator",
          bytecode +: dependencies,
          Seq(bytecode.toString, sources.toString, resources.toString),
          log
        )
        .get
      ((sources ** "*.java").get(), (resources ** "*").get().filter(_.isFile))
    },
    Jmh / run := {
      val arguments = spaceDelimited("<JMH option>").parsed
      val converter = fileConverter.value
      val classpath = (Jmh / fullClasspath).value.map(e => converter.toPath(e.data))
      val main = (Jmh / run / mainClass).value.getOrElse(sys.error("No JMH main class"))
      val executionRunner = (Jmh / run / runner).value
      val log = streams.value.log
      val format = arguments
        .sliding(2)
        .collectFirst {
          case pair if pair.size == 2 && pair.head == "-rf" => pair(1).toLowerCase
        }
        .getOrElse("text")
      if (!Set("html", "latex")(format)) executionRunner.run(main, classpath, arguments, log).get
      else {
        val outputIndex = arguments.indexOf("-rff")
        require(outputIndex < 0 || outputIndex + 1 < arguments.size, "Missing result output path")
        val name =
          if (outputIndex < 0) "jmh-result." + (if (format == "latex") "tex" else "html")
          else arguments(outputIndex + 1)
        val cwd = (Jmh / run / forkOptions).value.workingDirectory.getOrElse(file(sys.props("user.dir")))
        val selected = file(name)
        val output = if (selected.isAbsolute) selected else cwd / name
        val temporary = java.nio.file.Files.createTempFile("scala-benchmark-jmh-", ".json").toFile
        try {
          val forwarded = Vector.newBuilder[String]
          var i = 0
          while (i < arguments.size) {
            if (arguments(i) == "-rf" || arguments(i) == "-rff") i += 2
            else { forwarded += arguments(i); i += 1 }
          }
          val jsonArguments = forwarded.result() ++ Seq("-rf", "json", "-rff", temporary.getAbsolutePath)
          executionRunner.run(main, classpath, jsonArguments, log).get
          IO.write(output, JvmReport.render(temporary, format))
          log.info("Benchmark report: " + output.getAbsolutePath)
        } finally java.nio.file.Files.deleteIfExists(temporary.toPath)
      }
    },
    libraryDependencies ++= {
      if (platform.value == Platform.jvm && benchmarkUsePublishedArtifacts.value)
        Seq(
          "org.scala-native" %% "benchmark-interface" % BuildInfo.version,
          compilerPlugin("org.scala-native" % "benchmark-plugin" % BuildInfo.version)
            .cross(CrossVersion.full)
            .platform(Platform.jvm)
        )
      else Nil
    },
    Compile / scalacOptions ++= {
      if (platform.value == Platform.jvm) Seq("-P:scalanative-jmh:backend=jvm") else Nil
    }
  )
}
