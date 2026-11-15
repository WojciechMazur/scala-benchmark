/*
 * Copyright (c) 2026, Wojciech Mazur.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  The copyright holders
 * designate this particular file as subject to the "Classpath" exception
 * as provided in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package scala.benchmark.internal

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.util.Collections
import java.util.regex.Pattern

import scala.benchmark.Platform
import scala.jdk.CollectionConverters.*

import org.openjdk.jmh.runner.{BenchmarkList, BenchmarkListEntry}
import org.openjdk.jmh.runner.format.{OutputFormat, OutputFormatFactory}
import org.openjdk.jmh.runner.options.{Options, VerboseMode}

/** Applies platform eligibility to a JMH selection before upstream JMH runs it. */
object JvmPreflight:

  /** The options restricted to the benchmarks that run on the JVM, or `None` when nothing is left to run.
    *
    * @param listing
    *   print the selection instead of preparing a run
    * @param parameters
    *   when listing, also print parameter values
    */
  def resolve(
      options: Options,
      listing: Boolean = false,
      parameters: Boolean = false,
      format: OutputFormat = null
  ): Option[Options] =
    def emit(text: String): Unit = if format == null then println(text) else format.println(text)
    val upstream = upstreamBenchmarks()
    val discovered = inventory(upstream.map(_.getUsername).distinct)
    def select(includes: java.util.List[String], listing: Boolean): Selection.Result[InventoryEntry] =
      Selection(discovered, includes.asScala.toVector, options.getExcludes.asScala.toVector, Platform.JVM, listing)(
        _.name,
        _.eligibility
      )
    val selection = select(options.getIncludes, listing)
    val warmups =
      if listing || options.getWarmupIncludes.isEmpty then None
      else Some(select(options.getWarmupIncludes, listing = false))

    if listing then
      selection.selected.foreach { entry =>
        emit(s"${entry.name} [${entry.eligibility.description}]")
        if parameters then
          upstream.find(_.getUsername == entry.name).foreach(listParameters(_, options, emit))
      }
    else
      (selection.skipped ++ warmups.toVector.flatMap(_.skipped)).distinct.foreach { entry =>
        emit("# SKIPPED: " + entry.eligibility.diagnostic(entry.name, Platform.JVM))
      }

    val runnable = selection.selected.filter(_.eligibility.supports(Platform.JVM))
    Option.when(runnable.nonEmpty) {
      withIncludes(options, exactly(runnable), warmups.map(_.selected).filter(_.nonEmpty).map(exactly))
    }

  end resolve

  /** Every benchmark JMH generated. Projects may also contain Java benchmarks, which only JMH's list knows. */
  private def upstreamBenchmarks(): Vector[BenchmarkListEntry] =
    val silent = OutputFormatFactory.createFormatInstance(System.out, VerboseMode.SILENT)
    BenchmarkList
      .defaultList()
      .find(silent, Collections.singletonList(".*"), Collections.emptyList[String]())
      .asScala
      .toVector

  /** The generated inventory, completed with unrestricted entries for benchmarks it does not describe. */
  private def inventory(upstream: Vector[String]): Vector[InventoryEntry] =
    val generated = JvmInventory.load(Thread.currentThread().getContextClassLoader)
    val described = generated.map(_.name).toSet
    generated ++ upstream.filterNot(described).map(InventoryEntry(_, Eligibility()))

  private def listParameters(entry: BenchmarkListEntry, options: Options, emit: String => Unit): Unit =
    if entry.getParams.hasValue then
      entry.getParams.get().asScala.toVector.sortBy(_._1).foreach { (name, defaults) =>
        val values = options.getParameter(name).orElse(defaults.toVector.asJava)
        emit(s"  $name: ${values.asScala.mkString(", ")}")
      }

  /** A regular expression matching exactly the given benchmarks. */
  private def exactly(entries: Vector[InventoryEntry]): String =
    entries.map(entry => Pattern.quote(entry.name)).mkString("^(?:", "|", ")$")

  /** `OptionsBuilder.parent` would append the parent's includes, which reinterprets a canonical name as a regular
    * expression and may select more benchmarks. A proxy replaces the includes instead.
    */
  private def withIncludes(options: Options, includes: String, warmupIncludes: Option[String]): Options =
    val handler = new InvocationHandler with Serializable:
      def invoke(proxy: AnyRef, method: Method, arguments: Array[AnyRef]): AnyRef = method.getName match
        case "getIncludes"       => Collections.singletonList(includes)
        case "getWarmupIncludes" => warmupIncludes.fold(Collections.emptyList[String]())(Collections.singletonList)
        case _                   => method.invoke(options, Option(arguments).getOrElse(Array.empty[AnyRef])*)
    Proxy.newProxyInstance(classOf[Options].getClassLoader, Array(classOf[Options]), handler).asInstanceOf[Options]

end JvmPreflight
