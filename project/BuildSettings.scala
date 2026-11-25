import sbt.{*, given}
import sbt.Keys.*
import sbtbuildinfo.{BuildInfoKey, BuildInfoOption}
import sbtbuildinfo.BuildInfoKeys.*
import xsbti.FileConverter

object BuildSettings:
  val scalaTagsVersion = "0.13.1"
  val jmhVersion = "1.37"
  val projectOrganization = "io.github.wojciechmazur"
  val projectRepository = "WojciechMazur/scala-microbench-harness"
  val projectVersion = "1.0.0"

  val projectDescription =
    "Scala MicroBenchmark Harness (SMH): JMH benchmarks written in Scala 3 on the JVM, Scala Native, Scala.js and Wasm"

  /** Libraries and macros are built once with the LTS compiler; consumers may use any later Scala 3. */
  val libraryScalaVersion = "3.3.9"
  val libraryScalaVersions = Seq(libraryScalaVersion)
  val javaRelease = "17"
  val sbtScalaVersion = "3.9.0"

  /** Node.js host modules of the Scala.js backend, under `microbench/`. */
  val nodeHostResources = "runtime/js/src/main/resources"

  private def scalaString(text: String): String =
    text.flatMap {
      case '"'                     => "\\\""
      case '\\'                    => "\\\\"
      case '\n'                    => "\\n"
      case '\r'                    => "\\r"
      case '\t'                    => "\\t"
      case c if c < ' ' || c > '~' => "\\u%04x".format(c.toInt)
      case c                       => c.toString
    }.mkString("\"", "", "\"")

  /** Linked JavaScript cannot read classpath resources: the Scala.js runtime embeds the host as string constants, so
    * that a benchmark started by plain `node` (for example by scala-cli) can start the host itself.
    */
  val embeddedNodeHostSettings = Seq(
    Compile / sourceGenerators += Def.task {
      val directory = (LocalRootProject / baseDirectory).value / nodeHostResources / "microbench"
      val modules = (directory * "*.mjs").get().sortBy(_.getName)
      val entries = modules.map(file => s"    ${scalaString(file.getName)} -> ${scalaString(IO.read(file))}")
      val source =
        s"""package microbench.runtime
           |
           |/** Generated from $nodeHostResources/microbench. */
           |private[runtime] object NodeHostSources:
           |  val modules: Seq[(String, String)] = Seq(
           |${entries.mkString(",\n")}
           |  )
           |""".stripMargin
      val file = (Compile / sourceManaged).value / "microbench/runtime/NodeHostSources.scala"
      if !file.exists() || IO.read(file) != source then IO.write(file, source)
      Seq(file)
    }.taskValue
  )

  def reportingSources(root: File): Seq[File] =
    ((root / "runtime/shared/src/main/scala/microbench/internal/reporting") * "*.scala").get()

  val pluginBuildInfoSettings = Seq(
    buildInfoPackage := "microbench.sbtplugin",
    buildInfoKeys := Seq(
      BuildInfoKey(version),
      BuildInfoKey("organization" -> projectOrganization),
      BuildInfoKey("minimumScalaVersion" -> libraryScalaVersion),
      BuildInfoKey("jmhVersion" -> jmhVersion)
    ),
    buildInfoOptions += BuildInfoOption.PackagePrivate
  )

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
    name := s"microbench-$module",
    exportJars := true,
    scalaVersion := libraryScalaVersion,
    crossScalaVersions := Seq(libraryScalaVersion)
  ) ++ legalSettings

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

  val exampleSettings = Seq(
    scalaVersion := libraryScalaVersion,
    publish / skip := true,
    Compile / unmanagedSourceDirectories += (LocalRootProject / baseDirectory).value / "examples/shared/src/main/scala"
  )

end BuildSettings
