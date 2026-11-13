lazy val benchmarks = FixtureBuild.matrix("jvm", "native", "js")(_ => Nil)

lazy val root = project.in(file(".")).settings(FixtureBuild.commands)
