package scala.benchmark.internal

import java.util.regex.Pattern

import scala.jdk.CollectionConverters.*

import org.openjdk.jmh.runner.options.Options

object JvmPreflight:

  def resolve(
      options: Options,
      listing: Boolean = false,
      parameters: Boolean = false,
      format: org.openjdk.jmh.runner.format.OutputFormat = null
  ): Option[Options] =
    def emit(text: String): Unit = if format == null then println(text) else format.println(text)
    val loader = Thread.currentThread().getContextClassLoader
    val inventory = JvmInventory.load(loader)
    // Projects may also contain Java benchmarks, whose metadata is JMH's list.
    val upstream = org.openjdk.jmh.runner.BenchmarkList.defaultList()
    val output = org.openjdk.jmh.runner.format.OutputFormatFactory
      .createFormatInstance(System.out, org.openjdk.jmh.runner.options.VerboseMode.SILENT)
    val names = upstream
      .find(output, java.util.Collections.singletonList(".*"), java.util.Collections.emptyList[String]())
      .asScala
      .map(_.getUsername)
      .toVector
      .distinct
    val discovered = inventory ++
      names
        .filterNot(n => inventory.exists(_.name == n))
        .map(n => InventoryEntry(n, Eligibility()))
    val selection = Selection(
      discovered,
      options.getIncludes.asScala.toVector,
      options.getExcludes.asScala.toVector,
      scala.benchmark.Platform.JVM,
      listing
    )(_.name, _.eligibility)
    val warmups =
      if listing || options.getWarmupIncludes().isEmpty then None
      else
        Some(
          Selection(
            discovered,
            options.getWarmupIncludes().asScala.toVector,
            options.getExcludes().asScala.toVector,
            scala.benchmark.Platform.JVM
          )(_.name, _.eligibility)
        )
    if listing then
      selection.selected.foreach(e => emit(s"${e.name} [${e.eligibility.description}]"))
      if parameters then
        val entries = upstream
          .find(output, java.util.Collections.singletonList(".*"), java.util.Collections.emptyList[String]())
          .asScala
          .toVector
        selection.selected.foreach { selected =>
          entries.find(_.getUsername == selected.name).foreach { entry =>
            if entry.getParams.hasValue() then
              entry.getParams.get().asScala.toVector.sortBy(_._1).foreach { case (name, defaults) =>
                val values = options.getParameter(name).orElse(defaults.toVector.asJava)
                emit(s"  $name: ${values.asScala.mkString(", ")}")
              }
          }
        }
    else
      (selection.skipped ++ warmups.toVector.flatMap(_.skipped)).distinct.foreach(e =>
        emit("# SKIPPED: " + e.eligibility.diagnostic(e.name, scala.benchmark.Platform.JVM))
      )
    val runnable = selection.selected.filter(_.eligibility.supports(scala.benchmark.Platform.JVM))
    if runnable.isEmpty then return None
    val includes = runnable.map(e => Pattern.quote(e.name)).mkString("^(?:", "|", ")$")
    // OptionsBuilder.parent appends parent includes; that would reinterpret a
    // canonical literal as a regex and could select additional benchmarks.
    val resolved = java.lang.reflect.Proxy
      .newProxyInstance(
        classOf[Options].getClassLoader,
        Array(classOf[Options]),
        new java.lang.reflect.InvocationHandler with Serializable:
          def invoke(proxy: AnyRef, method: java.lang.reflect.Method, arguments: Array[AnyRef]): AnyRef =
            if method.getName == "getIncludes" then java.util.Collections.singletonList(includes)
            else if method.getName == "getWarmupIncludes" then
              warmups match
                case Some(selection) if selection.selected.nonEmpty =>
                  java.util.Collections
                    .singletonList(selection.selected.map(e => Pattern.quote(e.name)).mkString("^(?:", "|", ")$"))
                case _ => java.util.Collections.emptyList[String]()
            else method.invoke(options, Option(arguments).getOrElse(Array.empty[AnyRef])*)
      )
      .asInstanceOf[Options]
    Some(resolved)

  end resolve

end JvmPreflight
