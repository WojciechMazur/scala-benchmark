import scala.benchmark.sbtplugin.BenchmarkPlugin.autoImport.*

import BuildPlatforms.*
import BuildSettings.*
import ScriptedPlugin.autoImport.*
import pl.project13.scala.sbt.JmhPlugin
import sbt.{*, given}
import sbtbuildinfo.BuildInfoPlugin

organization := "org.scala-native"
version := projectVersion
scalaVersion := libraryScalaVersion
homepage := Some(uri("https://github.com/scala-native/scala-benchmark"))
licenses := Seq("GPLv2 with Classpath Exception" -> uri("https://openjdk.org/legal/gplv2+ce.html"))

lazy val root = project
  .in(file("."))
  .aggregate(
    (Seq(interface, runtime, examples).flatMap(_.projectRefs) :+ LocalProject(sbtPlugin.id)) *
  )
  .settings(name := "scala-benchmark", publish / skip := true)

lazy val interface = projectMatrix
  .in(file("interface"))
  .settings(librarySettings("interface"), sourceLayout("shared"))
  .libraryPlatforms(
    jvmSettings = sourceLayout("jvm") ++ Seq(
      libraryDependencies ++= Seq(
        "org.openjdk.jmh" % "jmh-core" % "1.37",
        "org.openjdk.jmh" % "jmh-generator-reflection" % "1.37"
      )
    ),
    nativeSettings = sourceLayout("non-jvm") ++ sourceLayout("native"),
    jsSettings = sourceLayout("non-jvm") ++ sourceLayout("js")
  )

lazy val runtime = projectMatrix
  .in(file("runtime"))
  .dependsOn(interface)
  .settings(
    librarySettings("runtime"),
    sourceLayout("shared"),
    libraryDependencies += "com.lihaoyi" %% "scalatags" % scalaTagsVersion
  )
  .libraryPlatforms(
    jvmSettings = sourceLayout("jvm-native") ++ sourceLayout("jvm") ++ junitSettings ++ harnessTestSettings ++ Seq(
      publish / skip := true,
      libraryDependencies += "org.apache.commons" % "commons-math3" % "3.6.1" % Test
    ),
    nativeSettings = sourceLayout("non-jvm") ++ sourceLayout("jvm-native") ++ sourceLayout("native"),
    jsSettings = sourceLayout("non-jvm") ++ sourceLayout("js")
  )

lazy val examples = projectMatrix
  .in(file("examples"))
  .dependsOn(runtime)
  .enablePlugins(JmhPlugin)
  .libraryPlatforms()
  .settings(
    exampleSettings,
    benchmarkUsePublishedArtifacts := false
  )

lazy val sbtPlugin = project
  .in(file("sbt-plugin"))
  .enablePlugins(SbtPlugin, ScriptedPlugin, BuildInfoPlugin)
  .settings(
    pluginBuildInfoSettings,
    Compile / unmanagedSources ++= reportingSources((LocalRootProject / baseDirectory).value),
    name := "sbt-scala-benchmark",
    libraryDependencies += "com.lihaoyi" %% "scalatags" % scalaTagsVersion,
    scalaVersion := sbtScalaVersion,
    crossScalaVersions := Seq(sbtScalaVersion),
    pluginCrossBuild / sbtVersion := "2.0.11",
    addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.12"),
    addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0"),
    addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.8"),
    scriptedBufferLog := false,
    scriptedLaunchOpts ++= Seq(
      "-Xmx1024m",
      "-Dplugin.version=" + version.value,
      "-Dbenchmark.scala.version=" + libraryScalaVersion,
      "-Dbenchmark.scripted.support=" +
        ((LocalRootProject / baseDirectory).value / "sbt-plugin/src/sbt-test-common").getAbsolutePath,
      "-Dbenchmark.test.sources=" +
        ((LocalRootProject / baseDirectory).value / "sbt-plugin/src/sbt-test-sources/scala").getAbsolutePath
    ) ++ sys.props.get("benchmark.platforms").map("-Dbenchmark.platforms=" + _),
    scriptedDependencies := {
      publishLocal.value
      (interface.jvm(true) / publishLocal).value
      (interface.native(true) / publishLocal).value
      (interface.js(true) / publishLocal).value
      (runtime.native(true) / publishLocal).value
      (runtime.js(true) / publishLocal).value
    }
  )
