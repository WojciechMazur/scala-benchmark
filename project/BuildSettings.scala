import sbt.{*, given}
import sbt.Keys.*
import sbtbuildinfo.{BuildInfoKey, BuildInfoOption}
import sbtbuildinfo.BuildInfoKeys.*
import xsbti.FileConverter

object BuildSettings:
  val scalaTagsVersion = "0.13.1"
  val projectVersion = "0.1.0-SNAPSHOT"

  /** Libraries and macros are built once with the LTS compiler; consumers may use any later Scala 3. */
  val libraryScalaVersion = "3.3.9"
  val javaRelease = "17"
  val sbtScalaVersion = "3.8.4"

  def reportingSources(root: File): Seq[File] =
    ((root / "runtime/shared/src/main/scala/scala/benchmark/internal/reporting") * "*.scala").get()

  val pluginBuildInfoSettings = Seq(
    buildInfoPackage := "scala.benchmark.sbtplugin",
    buildInfoKeys := Seq(BuildInfoKey(version), BuildInfoKey("minimumScalaVersion" -> libraryScalaVersion)),
    buildInfoOptions += BuildInfoOption.PackagePrivate
  )

  val compilerSettings = Seq(
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-java-output-version",
      javaRelease
    ),
    javacOptions ++= Seq("--release", javaRelease)
  )

  /** Unsafe-free lazy vals. Only the JVM artifacts need this encoding. */
  val futureLazyVals = Seq(scalacOptions += "-Yfuture-lazy-vals")

  val junitSettings = Seq(
    libraryDependencies ++= Seq(
      "com.github.sbt" % "junit-interface" % "0.13.3" % Test,
      "junit" % "junit" % "4.13.2" % Test
    )
  )

  /** Harness tests compile benchmark sources in-process, against the classpath of the runtime under test. */
  val harnessTestSettings = Seq(
    libraryDependencies += "org.scala-lang" %% "scala3-compiler" % scalaVersion.value % Test,
    Test / resourceGenerators += Def.task {
      val converter = fileConverter.value
      val classpath = (Compile / fullClasspath).value.map(entry => converter.toPath(entry.data).toAbsolutePath)
      val file = (Test / resourceManaged).value / "harness-test.classpath"
      IO.write(file, classpath.mkString(java.io.File.pathSeparator))
      Seq(file)
    }.taskValue
  )

  private def legalMappings(root: File, converter: FileConverter) =
    Seq("LICENSE", "NOTICE").map(name => converter.toVirtualFile((root / name).toPath) -> name)

  val legalSettings = Seq(
    Compile / packageBin / mappings ++= Def.uncached {
      legalMappings((LocalRootProject / baseDirectory).value, fileConverter.value)
    },
    Compile / packageSrc / mappings ++= Def.uncached {
      legalMappings((LocalRootProject / baseDirectory).value, fileConverter.value)
    }
  )

  def librarySettings(module: String) = Seq(
    name := s"benchmark-$module",
    exportJars := true,
    scalaVersion := libraryScalaVersion,
    crossScalaVersions := Seq(libraryScalaVersion)
  ) ++ compilerSettings ++ legalSettings

  def sourceLayout(directory: String) = Seq(Compile, Test).flatMap { configuration =>
    inConfig(configuration)(
      Seq(
        unmanagedSourceDirectories +=
          projectMatrixBaseDirectory.value.getAbsoluteFile / directory / "src" / sourceDirectory.value.getName /
            "scala",
        unmanagedResourceDirectories +=
          projectMatrixBaseDirectory.value.getAbsoluteFile / directory / "src" / sourceDirectory.value.getName /
            "resources"
      )
    )
  }

  val exampleSettings = compilerSettings ++ Seq(
    scalaVersion := libraryScalaVersion,
    publish / skip := true,
    Compile / unmanagedSourceDirectories += (LocalRootProject / baseDirectory).value / "examples/shared/src/main/scala"
  )

end BuildSettings
