import BuildSettings.*
import sbtbuildinfo.BuildInfoPlugin

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.13.2")
addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.13")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0")
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.8")
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.12.1")

Compile / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "sbt-plugin/src/main/scala"

// The build uses the plugin sources for the example matrices as well.
enablePlugins(BuildInfoPlugin)
version := projectVersion
pluginBuildInfoSettings

Compile / unmanagedResourceDirectories += baseDirectory.value.getParentFile / "sbt-plugin/src/main/resources"

// sbt loads meta-build classes directly after compilation, before packaging.
Compile / compile := Def.uncached {
  val analysis = (Compile / compile).value
  IO.copyDirectory(
    baseDirectory.value.getParentFile / "sbt-plugin/src/main/resources",
    (Compile / classDirectory).value
  )
  analysis
}

Compile / unmanagedSources ++= reportingSources(baseDirectory.value.getParentFile)

libraryDependencies += "com.lihaoyi" %% "scalatags" % scalaTagsVersion
