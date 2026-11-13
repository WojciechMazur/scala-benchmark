package scala.benchmark.sbtplugin

import sbt.Keys.*
import sbt.complete.DefaultParsers.spaceDelimited
import sbt.{*, given}

import java.nio.file.{Files, Path}
import java.util.jar.JarFile

import scala.collection.mutable.ArrayBuffer

import pl.project13.scala.sbt.JmhPlugin
import pl.project13.scala.sbt.JmhPlugin.JmhKeys.*
import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlatform
import xsbti.{FileConverter, HashedVirtualFileRef}

private object BenchmarkSbtFiles {
  def toFile(file: HashedVirtualFileRef)(using converter: FileConverter): File =
    converter.toPath(file).toFile
  def toNioPaths(files: Seq[Attributed[HashedVirtualFileRef]])(using
      converter: FileConverter
  ): Seq[Path] =
    files.map(file => converter.toPath(file.data))
  def crossScalaNative(module: ModuleID): ModuleID =
    module.platform(ScalaNativePlatform.current)
  def crossJVM(module: ModuleID): ModuleID = module.platform(sbt.Platform.jvm)
}
import BenchmarkSbtFiles.*
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport.{ScalaNativeCrossVersion => _, *}

/** Enable on a dedicated Native benchmark project sharing sources with sbt-jmh. */
object NativeBenchmarkPlugin extends AutoPlugin {
  override def requires: Plugins = ScalaNativePlugin && JmhPlugin
  override def trigger: PluginTrigger = allRequirements
  object autoImport {
    val benchmarkUsePublishedArtifacts = settingKey[Boolean](
      "Resolve the published JMH runtime and compiler plugin"
    )
    val benchmarkInventory = taskKey[Seq[File]](
      "Generate the embedded benchmark inventory and build metadata"
    )
  }

  import autoImport.*
  override def projectSettings: Seq[Setting[?]] = Seq(
    benchmarkUsePublishedArtifacts := true,
    libraryDependencies ~= (_.filterNot(_.organization == "org.openjdk.jmh")),
    libraryDependencies ++= {
      if (!benchmarkUsePublishedArtifacts.value) Nil
      else {
        Seq(
          crossScalaNative("org.scala-native" %% "benchmark-runtime" % BuildInfo.version),
          crossJVM(
            compilerPlugin("org.scala-native" % "benchmark-plugin" % BuildInfo.version)
              .cross(CrossVersion.full)
          )
        )
      }
    },

    // sbt-jmh owns the configuration and public keys. Compile the Jmh source
    // directories with the Native compiler instead of generating Java wrappers.
    Compile / unmanagedSourceDirectories ++= (Jmh / unmanagedSourceDirectories).value,
    Compile / mainClass := Some("scala.scalanative.jmh.Main"),
    Jmh / run / mainClass := (Compile / mainClass).value,
    Jmh / compile := Def.uncached { (Compile / compile).value },
    Jmh / sourceGenerators := Nil,
    Jmh / resourceGenerators := Nil,
    Jmh / JmhPlugin.generateJmhSourcesAndResources := Def.uncached {
      (Seq.empty[File], benchmarkInventory.value)
    },
    // Generated loops inline runtime templates that are not mentioned in the
    // user's source. Include their implementation in the compilation key.
    Compile / scalacOptions += Def.uncached {
      implicit val converter: FileConverter = fileConverter.value
      val digest = java.security.MessageDigest.getInstance("SHA-256")
      toNioPaths((Compile / dependencyClasspath).value)
        .filter(p =>
          p.getFileName.toString.startsWith("benchmark-runtime") && p.toString
            .endsWith(".jar")
        )
        .foreach(p => digest.update(Files.readAllBytes(p)))
      "-P:scalanative-jmh:runtimeKey=" + digest
        .digest()
        .map(b => f"${b & 0xff}%02x")
        .mkString
    },
    Compile / nativeConfig ~= { config =>
      config
        .withMode(scala.scalanative.build.Mode.releaseFull)
        .withMultithreading(true)
        .withEmbedResources(true)
        .withResourceIncludePatterns(
          config.resourceIncludePatterns :+ "native-jmh/**"
        )
    },
    benchmarkInventory := Def.uncached {
      require((Jmh / version).value == "1.37", "The Native backend currently supports JMH API 1.37")
      implicit val converter: FileConverter = fileConverter.value
      (Compile / compile).value
      removeOrphans((Compile / classDirectory).value.toPath)
      val output = (Compile / resourceManaged).value / "native-jmh"
      val dependencies = toNioPaths((Compile / dependencyClasspath).value)
      val entries =
        discover((Compile / classDirectory).value.toPath +: dependencies)
      val inventory = output / "benchmarks.list"
      def writeChanged(file: File, text: String): Unit =
        if (!file.exists() || IO.read(file) != text) IO.write(file, text)
      writeChanged(inventory, entries.mkString("", "\n", "\n"))
      val config = (Compile / nativeConfig).value
      val metadata = output / "build.properties"
      val properties = new java.util.Properties
      properties.setProperty("scalaVersion", scalaVersion.value)
      properties.setProperty("scalaNativeVersion", nativeVersion)
      properties.setProperty("mode", config.mode.name)
      properties.setProperty("lto", config.lto.name)
      properties.setProperty("gc", config.gc.name)
      properties.setProperty("compileOptions", config.compileOptions.mkString(" "))
      properties.setProperty("linkingOptions", config.linkingOptions.mkString(" "))
      val writer = new java.io.StringWriter
      properties.store(writer, null)
      val text = writer.toString.linesIterator
        .filterNot(_.startsWith("#"))
        .toVector
        .sorted
        .mkString("", "\n", "\n")
      writeChanged(metadata, text)
      Seq(inventory, metadata)
    },
    Compile / resourceGenerators += benchmarkInventory.taskValue,
    Jmh / nativeLink := Def.uncached { (Compile / nativeLink).value },
    Jmh / run := {
      val arguments = spaceDelimited("<JMH option>").parsed
      implicit val converter: FileConverter = fileConverter.value
      val executable = toFile((Compile / nativeLink).value).getAbsolutePath
      val exit = scala.sys.process
        .Process(
          executable +: arguments,
          baseDirectory.value,
          (Jmh / run / envVars).value.toSeq*
        )
        .!
      if (exit != 0) sys.error(s"Native JMH exited with $exit")
    }
  )

  /** Read compiler products, never load benchmark classes into sbt. */
  private[sbtplugin] def removeOrphans(root: Path): Unit = {
    if (!Files.isDirectory(root)) return
    val files = Files.walk(root)
    val modules = ArrayBuffer.empty[Path]
    try {
      val iterator = files.iterator()
      while (iterator.hasNext) {
        val path = iterator.next()
        val name = path.getFileName.toString
        if (
          Seq(".class", ".nir", ".sjsir")
            .exists(extension => name.endsWith("$nativejmh$bootstrapper$" + extension))
        ) modules += path
      }
    } finally files.close()
    modules.foreach { module =>
      val owner = module.getFileName.toString
        .stripSuffix(".class")
        .stripSuffix(".nir")
        .stripSuffix(".sjsir")
        .stripSuffix("$nativejmh$bootstrapper$")
      val directory = module.getParent
      if (
        !Files.exists(directory.resolve(owner + ".class")) && !Files.exists(
          directory.resolve(owner + "$.class")
        )
      ) {
        val prefix = owner + "$nativejmh$bootstrapper"
        val siblings = Files.list(directory)
        try {
          val iterator = siblings.iterator()
          while (iterator.hasNext) {
            val path = iterator.next()
            val name = path.getFileName.toString
            if (
              (name
                .startsWith(prefix + "$") || name.startsWith(prefix + ".")) &&
              Seq(".class", ".nir", ".sjsir", ".tasty").exists(name.endsWith)
            )
              Files.deleteIfExists(path)
          }
        } finally siblings.close()
      }
    }
  }

  def discover(classpath: Seq[Path]): Seq[String] = {
    val result = ArrayBuffer.empty[String]
    def accept(name: String, exists: String => Boolean): Unit = {
      val owner = name.stripSuffix("$nativejmh$bootstrapper$.class")
      if (
        name.endsWith("$nativejmh$bootstrapper$.class") &&
        (exists(owner + ".class") || exists(owner + "$.class"))
      )
        result += name
          .stripSuffix(".class")
          .replace('/', '.')
          .replace('\\', '.')
    }
    classpath.distinct.foreach { path =>
      if (Files.isDirectory(path)) {
        val files = Files.walk(path)
        try {
          val iterator = files.iterator()
          while (iterator.hasNext)
            accept(
              path.relativize(iterator.next()).toString,
              name => Files.exists(path.resolve(name))
            )
        } finally files.close()
      } else if (Files.isRegularFile(path) && path.toString.endsWith(".jar")) {
        val jar = new JarFile(path.toFile)
        try {
          val entries = jar.entries()
          while (entries.hasMoreElements)
            accept(
              entries.nextElement().getName,
              name => jar.getJarEntry(name) != null
            )
        } finally jar.close()
      }
    }
    result.distinct.sorted.toVector
  }
}
