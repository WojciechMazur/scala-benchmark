import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8

/** Resolves the short Scala versions used in CI, as scala-cli does: `3.lts` is the latest release of the newest LTS
  * series and `3.rc` the latest release candidate. Full versions are returned unchanged.
  */
object ScalaVersions:
  /** LTS series, oldest first. A new series is only used once it has a final release. */
  val ltsSeries = Seq("3.3", "3.9")

  private val metadata = "https://repo1.maven.org/maven2/org/scala-lang/scala3-compiler_3/maven-metadata.xml"

  private lazy val published: Seq[String] =
    val stream = URI.create(metadata).toURL.openStream()
    val text =
      try new String(stream.readAllBytes(), UTF_8)
      finally stream.close()
    "<version>([^<]+)</version>".r.findAllMatchIn(text).map(_.group(1)).toSeq

  private val Release = """(\d+)\.(\d+)\.(\d+)""".r
  private val Candidate = """(\d+)\.(\d+)\.(\d+)-RC(\d+)""".r

  /** Release candidates order before their release; nightlies and other suffixes are ignored. */
  private def key(version: String): Option[(Int, Int, Int, Int)] = version match
    case Release(major, minor, patch)       => Some((major.toInt, minor.toInt, patch.toInt, Int.MaxValue))
    case Candidate(major, minor, patch, rc) => Some((major.toInt, minor.toInt, patch.toInt, rc.toInt))
    case _                                  => None

  private def latest(versions: Seq[String]): Option[String] =
    versions.flatMap(v => key(v).map(v -> _)).sortBy(_._2).lastOption.map(_._1)

  def resolve(version: String): String = version match
    case "3.rc" =>
      latest(published.filter(_.contains("-RC"))).getOrElse(sys.error("No Scala 3 release candidate is published"))
    case "3.lts" =>
      ltsSeries.reverseIterator
        .flatMap(series => latest(published.filter(v => v.startsWith(series + ".") && !v.contains("-"))))
        .nextOption()
        .getOrElse(sys.error("No Scala 3 LTS release is published"))
    case full => full

end ScalaVersions
