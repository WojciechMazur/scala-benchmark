package scala.benchmark.internal

import java.nio.file.{Files, Path}
import java.util.jar.JarFile
import scala.jdk.CollectionConverters.*

/** Loads constant-only generated modules; never initializes user benchmark classes. */
object JvmInventory {
  def classpath(loader: ClassLoader): Vector[Path] = {
    def urls(current: ClassLoader): Vector[Path] = {
      if (current == null) Vector.empty
      else {
        val own = current match {
          case loader: java.net.URLClassLoader =>
            loader.getURLs.toVector
              .filter(_.getProtocol == "file")
              .map(url => java.nio.file.Paths.get(url.toURI))
          case _ => Vector.empty
        }
        own ++ urls(current.getParent)
      }
    }
    (urls(loader) ++ System
      .getProperty("java.class.path")
      .split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))
      .toVector
      .map(java.nio.file.Paths.get(_))).distinct
  }
  def load(classpath: Vector[Path], loader: ClassLoader): Vector[InventoryEntry] = {
    val suffix = "$nativejmh$bootstrapper$.class"
    val names = classpath.flatMap { path =>
      if (Files.isDirectory(path)) {
        val files = Files.walk(path)
        try
          files
            .iterator()
            .asScala
            .filter(p => p.toString.endsWith(suffix))
            .map(p => path.relativize(p).toString.replace(java.io.File.separatorChar, '.').stripSuffix(".class"))
            .toVector
        finally files.close()
      } else if (Files.isRegularFile(path) && path.toString.endsWith(".jar")) {
        val jar = new JarFile(path.toFile)
        try
          jar
            .entries()
            .asScala
            .map(_.getName)
            .filter(_.endsWith(suffix))
            .map(_.replace('/', '.').stripSuffix(".class"))
            .toVector
        finally jar.close()
      } else Vector.empty
    }
    names.distinct.sorted
      .flatMap { name =>
        val module = Class.forName(name, true, loader).getField("MODULE$").get(null)
        module.getClass
          .getMethod("metadata")
          .invoke(module)
          .asInstanceOf[Array[String]]
          .toVector
          .map(InventoryEntry.parse)
      }
      .groupBy(_.name)
      .toVector
      .sortBy(_._1)
      .map { case (name, members) =>
        InventoryEntry(
          name,
          Eligibility(
            members.foldLeft(scala.benchmark.Platform.values.toVector)((allowed, m) =>
              allowed.filter(m.eligibility.allowed.contains)
            ),
            members.flatMap(_.eligibility.reasons).distinct
          )
        )
      }
  }
}
