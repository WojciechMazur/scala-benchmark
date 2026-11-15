import scala.benchmark.sbtplugin.BenchmarkPlugin.autoImport.*

import BuildSettings.*
import ScriptedPlugin.autoImport.*
import pl.project13.scala.sbt.JmhPlugin
import sbt.{*, given}
import sbtbuildinfo.BuildInfoPlugin

organization := projectOrganization
version := projectVersion
scalaVersion := libraryScalaVersion
// sbt 2 applies bare settings to every subproject.
scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-java-output-version", javaRelease)

// Unsafe-free lazy vals for the JVM artifacts; Scala 3.8 and later use them by default.
scalacOptions ++= {
  if (platform.value == Platform.jvm && scalaVersion.value.startsWith("3.3.")) Seq("-Yfuture-lazy-vals") else Nil
}

javacOptions ++= Seq("--release", javaRelease)

homepage := Some(uri(s"https://github.com/$projectRepository"))

scmInfo := Some(
  ScmInfo(uri(s"https://github.com/$projectRepository"), s"scm:git:https://github.com/$projectRepository.git")
)

developers := List(
  Developer("WojciechMazur", "Wojciech Mazur", "", uri("https://github.com/WojciechMazur"))
)

licenses := Seq("GPLv2 with Classpath Exception" -> uri("https://openjdk.org/legal/gplv2+ce.html"))

/** `scriptedOn <platforms> [scala=<version>] <tests>` runs scripted tests on the given platforms, optionally with a
  * newer compiler in the benchmark projects.
  *
  * The selection is a launch option of the scripted builds. It is set here rather than read from a system property:
  * the build usually runs in a server that outlives the command line that started it.
  */
commands += Command.args("scriptedOn", "<platforms> [scala=<version>] <tests>") { (state, arguments) =>
  val platforms = arguments.headOption.getOrElse(sys.error("scriptedOn expects platforms, such as jvm,native"))
  val (versions, tests) = arguments.tail.partition(_.startsWith("scala="))
  val options =
    ("-Dbenchmark.platforms=" + platforms) +: versions.map("-Dbenchmark.scala.version=" + _.stripPrefix("scala="))
  val launchOptions = options.map("\"" + _ + "\"").mkString("Seq(", ", ", ")")
  // A selection made earlier in this session must not leak into this run.
  "session clear" ::
    s"set sbtPlugin / scriptedLaunchOpts ++= $launchOptions" ::
    s"sbtPlugin/scripted ${tests.mkString(" ")}" ::
    state
}

lazy val root = project
  .in(file("."))
  .aggregate(
    (Seq(interface, runtime, examples).flatMap(_.projectRefs) :+ LocalProject(sbtPlugin.id)) *
  )
  .settings(name := "scala-benchmark", publish / skip := true)

lazy val interface = projectMatrix
  .in(file("interface"))
  .settings(librarySettings("interface"), sourceLayout("shared"))
  .jvmPlatform(
    libraryScalaVersions,
    sourceLayout("jvm") ++ Seq(
      libraryDependencies ++= Seq(
        "org.openjdk.jmh" % "jmh-core" % jmhVersion,
        "org.openjdk.jmh" % "jmh-generator-reflection" % jmhVersion
      )
    )
  )
  .nativePlatform(libraryScalaVersions, sourceLayout("non-jvm") ++ sourceLayout("native"))
  .jsPlatform(libraryScalaVersions, sourceLayout("non-jvm") ++ sourceLayout("js"))

lazy val runtime = projectMatrix
  .in(file("runtime"))
  .dependsOn(interface)
  .settings(
    librarySettings("runtime"),
    sourceLayout("shared"),
    libraryDependencies += "com.lihaoyi" %% "scalatags" % scalaTagsVersion
  )
  // The JVM row only hosts the unit tests: JVM benchmarks run on upstream JMH.
  .jvmPlatform(
    libraryScalaVersions,
    sourceLayout("jvm-native") ++ sourceLayout("jvm") ++ junitSettings ++ harnessTestSettings ++ Seq(
      publish / skip := true,
      libraryDependencies += "org.apache.commons" % "commons-math3" % "3.6.1" % Test
    )
  )
  .nativePlatform(
    libraryScalaVersions,
    sourceLayout("non-jvm") ++ sourceLayout("jvm-native") ++ sourceLayout("native")
  )
  .jsPlatform(libraryScalaVersions, sourceLayout("non-jvm") ++ sourceLayout("js"))

lazy val examples = projectMatrix
  .in(file("examples"))
  .dependsOn(runtime)
  .enablePlugins(JmhPlugin)
  .jvmPlatform(libraryScalaVersions)
  .nativePlatform(libraryScalaVersions)
  .jsPlatform(libraryScalaVersions)
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
      "-Dplugin.organization=" + projectOrganization,
      "-Dbenchmark.scala.version=" + libraryScalaVersion,
      "-Dbenchmark.scripted.support=" +
        ((LocalRootProject / baseDirectory).value / "sbt-plugin/src/sbt-test-common").getAbsolutePath,
      "-Dbenchmark.test.sources=" +
        ((LocalRootProject / baseDirectory).value / "sbt-plugin/src/sbt-test-sources/scala").getAbsolutePath
    ),
    scriptedDependencies := {
      publishLocal.value
      (interface.jvm(libraryScalaVersion) / publishLocal).value
      (interface.native(libraryScalaVersion) / publishLocal).value
      (interface.js(libraryScalaVersion) / publishLocal).value
      (runtime.native(libraryScalaVersion) / publishLocal).value
      (runtime.js(libraryScalaVersion) / publishLocal).value
    }
  )
