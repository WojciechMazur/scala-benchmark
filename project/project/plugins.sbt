addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.13.2")

// Build-only settings are shared with the meta-build; plugin artifacts receive generated BuildInfo.
Compile / unmanagedSources += baseDirectory.value.getParentFile / "BuildSettings.scala"
