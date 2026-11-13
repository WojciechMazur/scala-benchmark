package scala.benchmark.internal

import java.io.File
import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.nio.file.{Files, Paths}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

import org.openjdk.jmh.annotations.{Benchmark, Group}
import org.openjdk.jmh.generators.core.*
import org.openjdk.jmh.generators.reflection.RFGeneratorSource

/** Filters only discovery; all harness generation and execution stay in JMH 1.37. */
object JvmGenerator:

  def main(args: Array[String]): Unit =
    require(args.length == 3, "Expected bytecode, source and resource directories")
    val loader = Thread.currentThread().getContextClassLoader
    val root = Paths.get(args(0))
    val inventory = JvmInventory.load(loader).map(e => e.name -> e.eligibility).toMap
    val source = new RFGeneratorSource
    val files = Files.walk(root)
    try
      files.iterator().asScala.filter(_.toString.endsWith(".class")).foreach { path =>
        val name = root.relativize(path).toString.replace(File.separatorChar, '.').stripSuffix(".class")
        source.processClasses(Class.forName(name, false, loader))
      }
    finally files.close()
    val wrappers = mutable.Map.empty[(String, String), ClassInfo]
    def wrap(info: ClassInfo, benchmarkClass: String): ClassInfo =
      if info == null then null
      else
        wrappers.getOrElseUpdate(
          (info.getQualifiedName, benchmarkClass),
          Proxy
            .newProxyInstance(
              classOf[ClassInfo].getClassLoader,
              Array(classOf[ClassInfo]),
              new InvocationHandler:
                def invoke(proxy: AnyRef, method: Method, arguments: Array[AnyRef]): AnyRef = method.getName match
                  case "getMethods" =>
                    info.getMethods.asScala
                      .filter { m =>
                        val group = Option(m.getAnnotation(classOf[Group])).map(_.value()).getOrElse(m.getName)
                        m.getAnnotation(classOf[Benchmark]) == null ||
                        inventory.get(benchmarkClass + "." + group).forall(_.supports(scala.benchmark.Platform.JVM))
                      }
                      .toVector
                      .asJava
                  case "getSuperClass" => wrap(info.getSuperClass, benchmarkClass)
                  case "equals"        => java.lang.Boolean.valueOf(proxy eq arguments(0))
                  case "hashCode"      => Integer.valueOf(System.identityHashCode(proxy))
                  case _               => method.invoke(info, Option(arguments).getOrElse(Array.empty[AnyRef])*)
            )
            .asInstanceOf[ClassInfo]
        )
    val filtered = new GeneratorSource:
      def getClasses(): java.util.Collection[ClassInfo] =
        source.getClasses.asScala.map(c => wrap(c, c.getQualifiedName)).toVector.asJava
      def resolveClass(name: String): ClassInfo = source.resolveClass(name)
    val destination = new FileSystemDestination(new File(args(2)), new File(args(1)))
    val generator = new BenchmarkGenerator
    generator.generate(filtered, destination)
    generator.complete(filtered, destination)
    require(!destination.hasErrors, destination.getErrors.asScala.mkString("\n"))

  end main

end JvmGenerator
