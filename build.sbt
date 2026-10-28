import sbt.{*, given}
import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport.*
import org.scalajs.sbtplugin.ScalaJSPlugin
import scala.benchmark.sbtplugin.{BenchmarkVersion, NativeBenchmarkPlugin}
import scala.benchmark.sbtplugin.NativeBenchmarkPlugin.autoImport.*

ThisBuild / organization := "org.scala-native"
ThisBuild / version := BenchmarkVersion.value
ThisBuild / scalaVersion := "3.3.8"
ThisBuild / crossScalaVersions := Seq("3.3.8", "3.9.0")
ThisBuild / homepage := Some(uri("https://github.com/scala-native/scala-benchmark"))
ThisBuild / licenses := Seq(
  "GPLv2 with Classpath Exception" -> uri("https://openjdk.org/legal/gplv2+ce.html")
)
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked")

def sharedSettings(module: String) = Seq(
  name := "benchmark-" + module,
  exportJars := true,
  Compile / unmanagedSourceDirectories += (ThisBuild / baseDirectory).value / module / "shared/src/main/scala",
  Compile / packageBin / mappings ++= Def.uncached {
    val root = (ThisBuild / baseDirectory).value
    val converter = fileConverter.value
    Seq("LICENSE", "NOTICE").map(name => converter.toVirtualFile((root / name).toPath) -> name)
  },
  Compile / packageSrc / mappings ++= Def.uncached {
    val root = (ThisBuild / baseDirectory).value
    val converter = fileConverter.value
    Seq("LICENSE", "NOTICE").map(name => converter.toVirtualFile((root / name).toPath) -> name)
  }
)

lazy val root = project
  .in(file("."))
  .aggregate(
    coreJVM,
    coreNative,
    coreJS,
    jmhApiJVM,
    jmhApiNative,
    jmhApiJS,
    runtimeJVM,
    runtimeNative,
    runtimeJS,
    compilerPlugin,
    nativeExamples,
    jvmExamples,
    jsExamples,
    sbtScalaBenchmark
  )
  .settings(name := "scala-benchmark", publish / skip := true)

lazy val coreJVM = project.in(file("core/jvm")).settings(sharedSettings("core"))
lazy val coreNative =
  project.in(file("core/native")).enablePlugins(ScalaNativePlugin).settings(sharedSettings("core"))
lazy val coreJS =
  project.in(file("core/js")).enablePlugins(ScalaJSPlugin).settings(sharedSettings("core"))

lazy val jmhApiJVM = project.in(file("jmh-api/jvm")).settings(sharedSettings("jmh-api"))
lazy val jmhApiNative = project
  .in(file("jmh-api/native"))
  .enablePlugins(ScalaNativePlugin)
  .settings(sharedSettings("jmh-api"))
lazy val jmhApiJS =
  project.in(file("jmh-api/js")).enablePlugins(ScalaJSPlugin).settings(sharedSettings("jmh-api"))

lazy val runtimeJVM = project
  .in(file("runtime/jvm"))
  .dependsOn(coreJVM, jmhApiJVM)
  .settings(sharedSettings("runtime"))
  .settings(
    publish / skip := true, // Deterministic test backend; JVM benchmarks use upstream JMH.
    libraryDependencies ++= Seq(
      "com.github.sbt" % "junit-interface" % "0.13.3" % Test,
      "junit" % "junit" % "4.13.2" % Test,
      "org.apache.commons" % "commons-math3" % "3.6.1" % Test
    )
  )

lazy val runtimeNative = project
  .in(file("runtime/native"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(coreNative, jmhApiNative)
  .settings(sharedSettings("runtime"))

lazy val runtimeJS = project
  .in(file("runtime/js"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(coreJS, jmhApiJS)
  .settings(name := "benchmark-runtime", publish / skip := true)

lazy val compilerPlugin = project
  .in(file("compiler-plugin"))
  .dependsOn(runtimeJVM % "test->compile")
  .settings(
    name := "benchmark-plugin",
    crossVersion := CrossVersion.full,
    exportJars := true,
    libraryDependencies ++= Seq(
      "org.scala-lang" %% "scala3-compiler" % scalaVersion.value % Provided,
      "com.github.sbt" % "junit-interface" % "0.13.3" % Test,
      "junit" % "junit" % "4.13.2" % Test
    ),
    Test / fork := true,
    Test / javaOptions ++= Def.uncached {
      val converter = fileConverter.value
      Seq(
        "-Dscalanative.jmh.plugin=" + converter.toPath((Compile / packageBin).value).toAbsolutePath,
        "-Dscalanative.jmh.testClasspath=" + (Test / fullClasspath).value
          .map(entry => converter.toPath(entry.data).toAbsolutePath)
          .mkString(java.io.File.pathSeparator)
      )
    },
    Compile / packageBin / mappings ++= Def.uncached {
      val converter = fileConverter.value
      Seq("LICENSE", "NOTICE")
        .map(name => converter.toVirtualFile((baseDirectory.value / name).toPath) -> name)
    },
    Compile / packageSrc / mappings ++= Def.uncached {
      val converter = fileConverter.value
      Seq("LICENSE", "NOTICE")
        .map(name => converter.toVirtualFile((baseDirectory.value / name).toPath) -> name)
    }
  )

lazy val nativeExamples = project
  .in(file("examples/native"))
  .enablePlugins(ScalaNativePlugin, pl.project13.scala.sbt.JmhPlugin)
  .dependsOn(runtimeNative)
  .settings(
    publish / skip := true,
    benchmarkUsePublishedArtifacts := false,
    Compile / unmanagedSourceDirectories += (ThisBuild / baseDirectory).value / "examples/shared/src/main/scala",
    Compile / scalacOptions ++= Def.uncached {
      val jar = fileConverter.value.toPath((compilerPlugin / Compile / packageBin).value)
      Seq(
        "-Xplugin:" + jar.toAbsolutePath,
        "-P:scalanative-jmh:cacheKey=" + sbt.io.Hash.toHex(sbt.io.Hash(jar.toFile))
      )
    }
  )

lazy val jvmExamples = project
  .in(file("examples/jvm"))
  .enablePlugins(pl.project13.scala.sbt.JmhPlugin)
  .settings(
    publish / skip := true,
    Compile / unmanagedSourceDirectories += (ThisBuild / baseDirectory).value / "examples/shared/src/main/scala"
  )

lazy val sbtScalaBenchmark = project
  .in(file("sbt-scala-benchmark"))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-scala-benchmark",
    scalaVersion := "3.8.4",
    crossScalaVersions := Seq("3.8.4"),
    pluginCrossBuild / sbtVersion := "2.0.11",
    addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.12"),
    addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0"),
    addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.8"),
    Compile / unmanagedSources += (ThisBuild / baseDirectory).value / "project/BenchmarkVersion.scala"
  )

lazy val jsExamples = project
  .in(file("examples/js"))
  .enablePlugins(ScalaJSPlugin, pl.project13.scala.sbt.JmhPlugin)
  .dependsOn(runtimeJS)
  .settings(publish / skip := true)
