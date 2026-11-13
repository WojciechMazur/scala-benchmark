import sbt.{*, given}
import sbt.Keys.*
import xsbti.FileConverter
import sbtbuildinfo.{BuildInfoKey, BuildInfoOption}
import sbtbuildinfo.BuildInfoKeys.*

object BuildSettings {
  val scalaTagsVersion = "0.13.1"
  val projectVersion = "0.1.0-SNAPSHOT"
  val supportedScalaVersions = (3 to 9).map(patch => s"3.3.$patch") ++ Seq("3.9.0", "3.10.0")
  val minimumScalaVersion = supportedScalaVersions.head
  val defaultScalaVersion = "3.3.9"
  val sbtScalaVersion = "3.8.4"

  def reportingSources(root: File): Seq[File] =
    ((root / "runtime/shared/src/main/scala/scala/benchmark/internal/reporting") * "*.scala").get()

  val pluginBuildInfoSettings = Seq(
    buildInfoPackage := "scala.benchmark.sbtplugin",
    buildInfoKeys := Seq(BuildInfoKey(version), BuildInfoKey("supportedScalaVersions" -> supportedScalaVersions)),
    buildInfoOptions += BuildInfoOption.PackagePrivate
  )

  val junitSettings = Seq(
    libraryDependencies ++= Seq(
      "com.github.sbt" % "junit-interface" % "0.13.3" % Test,
      "junit" % "junit" % "4.13.2" % Test
    )
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
    scalaVersion := minimumScalaVersion,
    crossScalaVersions := supportedScalaVersions,
    // Publish the binary-compatible API with the oldest supported compiler.
    publish / skip := scalaVersion.value != minimumScalaVersion,
    publishLocal / skip := (publish / skip).value
  ) ++ legalSettings

  def sourceLayout(directory: String) = Seq(Compile, Test).flatMap { configuration =>
    inConfig(configuration)(
      Seq(
        unmanagedSourceDirectories +=
          projectMatrixBaseDirectory.value.getAbsoluteFile / directory / "src" / sourceDirectory.value.getName / "scala",
        unmanagedResourceDirectories +=
          projectMatrixBaseDirectory.value.getAbsoluteFile / directory / "src" / sourceDirectory.value.getName / "resources"
      )
    )
  }

  val exampleSettings = Seq(
    scalaVersion := defaultScalaVersion,
    publish / skip := true,
    Compile / unmanagedSourceDirectories += (LocalRootProject / baseDirectory).value / "examples/shared/src/main/scala"
  )

}
