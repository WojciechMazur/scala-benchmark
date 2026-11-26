// The README setup, verbatim apart from the Scala version under test: only JmhPlugin is enabled, and the
// microbench plugins and libraries must follow from it.
val scalaVersions = Seq(sys.props("microbench.scala.version"))

val checkResults = taskKey[Unit]("Check that the last run wrote results.json, then remove it")

lazy val benchmarks = projectMatrix
  .in(file("benchmarks"))
  .enablePlugins(JmhPlugin)
  .jvmPlatform(scalaVersions)
  .nativePlatform(scalaVersions)
  .jsPlatform(scalaVersions)
  .settings(
    // Upstream JMH refuses to run while another JMH run holds its lock, such as in a parallel scripted test.
    Jmh / javaOptions += "-Djmh.ignoreLock=true",
    // Upstream JMH writes in the build directory, Native and Scala.js in the project directory.
    checkResults := {
      val written = Seq(baseDirectory.value, (LocalRootProject / baseDirectory).value)
        .map(_ / "results.json")
        .filter(_.isFile)
      assert(written.size == 1, "Expected one results.json, found: " + written.mkString(", "))
      val results = IO.read(written.head)
      assert(results.contains("\"example.Example.run\""), results)
      IO.delete(written.head)
    }
  )

// Manual registration, without the plugin: a main method lists the suites.
lazy val manual = projectMatrix
  .in(file("manual"))
  // Forked trials start the running JVM again, which must not be sbt itself.
  .jvmPlatform(scalaVersions, Seq(run / fork := true))
  .nativePlatform(scalaVersions)
  .settings(
    libraryDependencies += sys.props("plugin.organization") %% "microbench" % sys.props("plugin.version"),
    Compile / unmanagedSourceDirectories += (LocalRootProject / baseDirectory).value / "benchmarks/src/main/scala"
  )
