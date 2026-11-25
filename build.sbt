import _root_.microbench.sbtplugin.MicrobenchPlugin.autoImport.*
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
  * newer compiler in the benchmark projects. The version may be short: `3.lts` or `3.rc` (see `ScalaVersions`).
  *
  * The selection is a launch option of the scripted builds. It is set here rather than read from a system property:
  * the build usually runs in a server that outlives the command line that started it.
  */
commands += Command.args("scriptedOn", "<platforms> [scala=<version>] <tests>") { (state, arguments) =>
  val platforms = arguments.headOption.getOrElse(sys.error("scriptedOn expects platforms, such as jvm,native"))
  val (versions, tests) = arguments.tail.partition(_.startsWith("scala="))
  val options = ("-Dmicrobench.platforms=" + platforms) +:
    versions.map(v => "-Dmicrobench.scala.version=" + ScalaVersions.resolve(v.stripPrefix("scala=")))
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
    (Seq(interface, runtime, microbench, examples).flatMap(_.projectRefs) :+ LocalProject(sbtPlugin.id)) *
  )
  .settings(name := "microbench-root", publish / skip := true)

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
  // The JVM row runs benchmarks started directly, such as with scala-cli; sbt-jmh projects keep upstream JMH.
  .jvmPlatform(
    libraryScalaVersions,
    sourceLayout("jvm-native") ++ sourceLayout("jvm") ++ junitSettings ++ harnessTestSettings ++ Seq(
      libraryDependencies += "org.apache.commons" % "commons-math3" % "3.6.1" % Test
    )
  )
  .nativePlatform(
    libraryScalaVersions,
    sourceLayout("non-jvm") ++ sourceLayout("jvm-native") ++ sourceLayout("native")
  )
  .jsPlatform(libraryScalaVersions, sourceLayout("non-jvm") ++ sourceLayout("js") ++ embeddedNodeHostSettings)

/** `microbench`: the single dependency for running benchmarks without the sbt plugin, such as with scala-cli.
  * It has no code of its own and depends on the interface and the runtime.
  */
lazy val microbench = projectMatrix
  .in(file("microbench"))
  .dependsOn(interface, runtime)
  .settings(librarySettings("aggregate"), name := "microbench")
  .jvmPlatform(libraryScalaVersions)
  .nativePlatform(libraryScalaVersions)
  .jsPlatform(libraryScalaVersions)

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
    // The Node.js host is shared with the Scala.js runtime, which embeds it for launchers other than sbt.
    Compile / unmanagedResourceDirectories += (LocalRootProject / baseDirectory).value / nodeHostResources,
    name := "sbt-microbench",
    libraryDependencies += "com.lihaoyi" %% "scalatags" % scalaTagsVersion,
    scalaVersion := sbtScalaVersion,
    crossScalaVersions := Seq(sbtScalaVersion),
    pluginCrossBuild / sbtVersion := "2.1.1",
    addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.13"),
    addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0"),
    addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.8"),
    scriptedBufferLog := false,
    scriptedLaunchOpts ++= Seq(
      "-Xmx1024m",
      "-Dstdout.encoding=UTF-8",
      "-Dstderr.encoding=UTF-8",
      "-Dplugin.version=" + version.value,
      "-Dplugin.organization=" + projectOrganization,
      "-Dmicrobench.scala.version=" + libraryScalaVersion,
      "-Dmicrobench.scripted.support=" +
        ((LocalRootProject / baseDirectory).value / "sbt-plugin/src/sbt-test-common").getAbsolutePath,
      "-Dmicrobench.test.sources=" +
        ((LocalRootProject / baseDirectory).value / "sbt-plugin/src/sbt-test-sources/scala").getAbsolutePath
    ),
    scriptedDependencies := {
      publishLocal.value
      (interface.jvm(libraryScalaVersion) / publishLocal).value
      (interface.native(libraryScalaVersion) / publishLocal).value
      (interface.js(libraryScalaVersion) / publishLocal).value
      (runtime.jvm(libraryScalaVersion) / publishLocal).value
      (runtime.native(libraryScalaVersion) / publishLocal).value
      (runtime.js(libraryScalaVersion) / publishLocal).value
      (microbench.jvm(libraryScalaVersion) / publishLocal).value
      (microbench.native(libraryScalaVersion) / publishLocal).value
      (microbench.js(libraryScalaVersion) / publishLocal).value
    }
  )
