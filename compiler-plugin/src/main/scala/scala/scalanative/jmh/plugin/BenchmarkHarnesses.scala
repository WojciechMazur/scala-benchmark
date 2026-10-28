/*
 * Copyright (c) 2005, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package scala.scalanative.jmh.plugin

import dotty.tools.dotc.ast.tpd._
import dotty.tools.dotc.core.Annotations.Annotation
import dotty.tools.dotc.core.Constants._
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Decorators._
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.Names._
import dotty.tools.dotc.core.Scopes._
import dotty.tools.dotc.core.StdNames._
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Types._
import dotty.tools.dotc.core._
import dotty.tools.dotc.plugins.PluginPhase
import dotty.tools.dotc.{report, transform}
import scala.collection.mutable

/** Expands the loop templates before Scala inlining. Every benchmark call and invocation fixture
  * becomes part of the generated loop, with no callback. The Native backend subsequently processes
  * these trees like ordinary Scala.
  */
final class BenchmarkHarnesses extends PluginPhase {
  val phaseName = "scalanative-jmh-harnesses"
  override val runsAfter = Set(transform.PostTyper.name)
  override val runsBefore = Set(transform.Pickler.name)
  private val prefix = "org.openjdk.jmh.annotations."
  private val runtime = "scala.scalanative.jmh."
  private val suffix = "$nativejmh$bootstrapper"
  private class Invalid extends RuntimeException with scala.util.control.NoStackTrace

  private def fail(message: String, symbol: Symbol)(using Context): Nothing = {
    report.error(message, symbol.sourcePos)
    throw new Invalid
  }
  private def annotation(symbol: Symbol, name: String)(using
      Context
  ): Option[Annotation] =
    symbol.annotations.find(_.symbol.fullName.toString == prefix + name)
  private def inherited(symbol: ClassSymbol, name: String)(using
      Context
  ): Option[Annotation] =
    symbol.baseClasses.iterator
      .flatMap(s => annotation(s, name))
      .take(1)
      .toList
      .headOption
  private def methods(symbol: ClassSymbol)(using Context): List[Symbol] =
    symbol.info.allMembers.iterator
      .flatMap(_.alternatives.map(_.symbol))
      .filter(s => s.is(Method) && !s.isConstructor)
      .toList
      .distinct
  private def fields(symbol: ClassSymbol)(using Context): List[Symbol] =
    symbol.baseClasses.reverse
      .flatMap(
        _.info.decls
          .filter(s => !s.is(Method) && annotation(s, "Param").nonEmpty)
      )
      .distinct
  private def fixtures(symbol: ClassSymbol, setup: Boolean)(using
      Context
  ): List[Symbol] = {
    val all =
      methods(symbol).filter(s => annotation(s, if (setup) "Setup" else "TearDown").nonEmpty)
    val order = symbol.baseClasses.reverse.zipWithIndex.toMap
    val sorted =
      all.sortBy(s => (order.getOrElse(s.owner.asClass, 0), s.name.toString))
    if (setup) sorted else sorted.reverse
  }
  private def arguments(
      annotation: Annotation
  )(using Context): Map[String, Tree] = {
    val names = annotation.symbol.primaryConstructor.paramSymss.flatten
      .map(_.name.toString)
    annotation.arguments.zipWithIndex.map {
      case (NamedArg(name, value), _) => name.toString -> value
      case (value, index)             =>
        names.lift(index).getOrElse(index.toString) -> value
    }.toMap
  }
  private def binding(tree: Tree)(using Context): Option[Tree] = {
    if (!tree.symbol.exists || enumNames(tree.symbol.name.toString)) None
    else
      tree.symbol.defTree match {
        case v: ValDef if !v.rhs.isEmpty && v.rhs.symbol != tree.symbol =>
          Some(v.rhs)
        case _ => None
      }
  }
  private val enumNames = Set(
    "Thread",
    "Benchmark",
    "Group",
    "Trial",
    "Iteration",
    "Invocation",
    "Throughput",
    "AverageTime",
    "SampleTime",
    "SingleShotTime",
    "All",
    "NANOSECONDS",
    "MICROSECONDS",
    "MILLISECONDS",
    "SECONDS",
    "MINUTES",
    "HOURS",
    "DAYS",
    "INLINE",
    "DONT_INLINE",
    "EXCLUDE",
    "COMPILE_ONLY",
    "PRINT",
    "BREAK"
  )
  private def constant(tree: Tree)(using Context): Option[String] = tree match {
    case Literal(c)                         => Option(c.value).map(_.toString)
    case NamedArg(_, value)                 => constant(value)
    case Typed(value, _)                    => constant(value)
    case Inlined(_, _, value)               => constant(value)
    case _: Ident if binding(tree).nonEmpty => constant(binding(tree).get)
    case _
        if tree.symbol.exists && !tree.symbol.name.toString.contains(
          "default$"
        ) =>
      tree.tpe.widenTermRefExpr match {
        case ConstantType(c) => Some(c.value.toString)
        case _               =>
          Option.when(enumNames(tree.symbol.name.toString))(
            tree.symbol.name.toString
          )
      }
    case _ => None
  }
  private def strings(tree: Tree)(using Context): List[String] = tree match {
    case Literal(c) if c.value.isInstanceOf[String] => List(c.stringValue)
    case SeqLiteral(elements, _)                    => elements.flatMap(strings)
    case Apply(fun, args)                           => strings(fun) ::: args.flatMap(strings)
    case TypeApply(fun, _)                          => strings(fun)
    case Typed(value, _)                            => strings(value)
    case Inlined(_, _, value)                       => strings(value)
    case _: Ident                                   => binding(tree).toList.flatMap(strings)
    case _                                          => Nil
  }
  private def enums(tree: Tree)(using Context): List[String] = tree match {
    case SeqLiteral(elements, _)            => elements.flatMap(enums)
    case Apply(fun, args)                   => enums(fun) ::: args.flatMap(enums)
    case TypeApply(fun, _)                  => enums(fun)
    case Typed(value, _)                    => enums(value)
    case Inlined(_, _, value)               => enums(value)
    case _: Ident if binding(tree).nonEmpty => enums(binding(tree).get)
    case _                                  =>
      constant(tree).toList.filter(
        Set("Throughput", "AverageTime", "SampleTime", "SingleShotTime", "All")
      )
  }
  private def value(ann: Option[Annotation], name: String, default: String)(using
      Context
  ): String =
    ann
      .flatMap(a => arguments(a).get(name))
      .flatMap(constant)
      .getOrElse(default)
  private def level(method: Symbol, setup: Boolean)(using Context): Int =
    value(
      annotation(method, if (setup) "Setup" else "TearDown"),
      "value",
      "Trial"
    ) match {
      case "Trial"      => 0
      case "Iteration"  => 1
      case "Invocation" => 2
      case other        => fail(s"Unknown fixture level $other", method)
    }
  private def parameters(method: Symbol)(using Context): List[Type] = {
    def loop(tpe: Type): List[Type] = tpe match {
      case mt: MethodType => mt.paramInfos ::: loop(mt.resType)
      case _: PolyType    =>
        fail("Generic benchmark and fixture methods are unsupported", method)
      case _ => Nil
    }
    loop(method.info)
  }
  private def addControl(symbol: Symbol, control: String)(using Context): Unit =
    control match {
      case "DONT_INLINE" =>
        symbol.addAnnotation(requiredClass("scala.noinline"))
      case "INLINE" =>
        symbol.addAnnotation(
          requiredClass("scala.scalanative.annotation.alwaysinline")
        )
      case other =>
        fail(
          s"CompilerControl.$other has no supported Native equivalent",
          symbol
        )
    }

  override def transformDefDef(tree: DefDef)(using Context): Tree = {
    try {
      val control = annotation(tree.symbol, "CompilerControl").orElse(
        annotation(tree.symbol.owner, "CompilerControl")
      )
      control.foreach(a => addControl(tree.symbol, value(Some(a), "value", "")))
      val explicitInlining = tree.symbol.annotations.exists(a =>
        Set(
          "scala.noinline",
          "scala.inline",
          "scala.scalanative.annotation.alwaysinline",
          "scala.scalanative.annotation.nooptimize"
        )(a.symbol.fullName.toString)
      )
      if (
        control.isEmpty && annotation(
          tree.symbol,
          "Benchmark"
        ).nonEmpty && !explicitInlining
      )
        tree.symbol.addAnnotation(requiredClass("scala.inline"))
    } catch { case _: Invalid => () }
    tree
  }

  // These assignment bridges are pickled, allowing states in other compilation
  // units to be initialized without Java reflection or private-field access.
  private def bridgeName(field: Symbol)(using Context): TermName = {
    val owner = field.owner.fullName.toString
      .getBytes(java.nio.charset.StandardCharsets.UTF_8)
      .map(b => f"${b & 0xff}%02x")
      .mkString
    ("$nativejmh$set$" + owner + "$" + field.name.toString).toTermName
  }
  override def transformTemplate(tree: Template)(using Context): Tree = {
    val owner = ctx.owner.asClass
    val bridges = tree.body
      .collect {
        case field: ValDef if annotation(field.symbol, "Param").nonEmpty =>
          if (!field.symbol.is(Mutable)) {
            report.error("@Param requires a mutable var", field.sourcePos)
            EmptyTree
          } else {
            val name = bridgeName(field.symbol)
            val bridge = newSymbol(
              owner,
              name,
              Synthetic | Method,
              MethodType(
                List("value".toTermName),
                List(field.symbol.info),
                defn.UnitType
              )
            ).entered
            DefDef(
              bridge.asTerm,
              args => Assign(This(owner).select(field.symbol), args.head.head)
            )
          }
      }
      .filterNot(_.isEmpty)
    cpy.Template(tree)(body = tree.body ::: bridges)
  }

  override def transformPackageDef(tree: PackageDef)(using Context): Tree = {
    // Empty suites replace old metadata when the last @Benchmark is removed,
    // or when a formerly concrete benchmark becomes abstract or an object.
    val candidates = tree.stats.collect {
      case d: TypeDef if d.symbol.isClass => d.symbol.asClass
    }
    val generated = candidates.groupBy(className).toList.sortBy(_._1).flatMap { case (_, classes) =>
      val cls = classes.find(!_.is(ModuleClass)).getOrElse(classes.head)
      try generate(cls).toList
      catch { case _: Invalid => Nil }
    }
    cpy.PackageDef(tree)(tree.pid, tree.stats ::: generated)
  }

  private def className(cls: ClassSymbol)(using Context): String =
    if (cls.is(ModuleClass)) cls.name.toString.stripSuffix("$")
    else cls.name.toString

  private def generate(benchmarkClass: ClassSymbol)(using Context): Tree = {
    // The source unit may contain no user-written inline call. Inlining skips
    // such units unless generators explicitly request traversal.
    ctx.compilationUnit.needsInlining = true
    val benchmarks =
      (if (benchmarkClass.isOneOf(ModuleClass | Abstract | Trait)) Nil
       else methods(benchmarkClass))
        .filter(annotation(_, "Benchmark").nonEmpty)
        .sortBy(_.name.toString)
    if (benchmarks.map(_.name).distinct.size != benchmarks.size)
      fail("Overloaded @Benchmark methods are unsupported", benchmarkClass)
    val states = mutable.ArrayBuffer.empty[ClassSymbol]
    val dependencies = mutable.Map.empty[ClassSymbol, List[ClassSymbol]]
    val active = mutable.Set.empty[ClassSymbol]
    val special = Set(
      "org.openjdk.jmh.infra.Blackhole",
      "org.openjdk.jmh.infra.ThreadParams"
    )
    def stateArguments(method: Symbol): List[ClassSymbol] =
      parameters(method).flatMap { tpe =>
        val cls = tpe.classSymbol
        if (special(cls.fullName.toString)) Nil
        else if (
          cls.exists && cls.isClass && inherited(
            cls.asClass,
            "State"
          ).nonEmpty
        ) List(cls.asClass)
        else
          fail(
            "Arguments must be @State classes, Blackhole or ThreadParams",
            method
          )
      }
    def visit(cls: ClassSymbol): Unit = {
      if (active(cls)) fail("Cycle in @State dependencies", cls)
      if (!states.contains(cls)) {
        if (
          cls.isOneOf(Abstract | Trait | ModuleClass) || !cls.owner.is(
            Package
          )
        )
          fail("Benchmark states must be concrete top-level classes", cls)
        if (!cls.info.decls.exists(s => s.isConstructor && parameters(s).isEmpty && !s.is(Private)))
          fail("Benchmark states require a public no-argument constructor", cls)
        val scope = value(inherited(cls, "State"), "value", "Thread")
        if (scope == "Group")
          fail("Scope.Group is not supported by Native JMH yet", cls)
        if (annotation(cls, "AuxCounters").nonEmpty)
          fail("AuxCounters is not supported by Native JMH yet", cls)
        active += cls
        val helpers = fixtures(cls, true) ::: fixtures(cls, false)
        helpers.foreach { m =>
          if (
            m.isOneOf(
              Private | Protected
            ) || m.privateWithin.exists || !(m.info.finalResultType =:= defn.UnitType)
          )
            fail("Fixture methods must be public and return Unit", m)
        }
        val deps = helpers.flatMap(stateArguments).distinct
        dependencies(cls) = deps
        deps.foreach(visit)
        active -= cls
        states += cls
      }
    }
    if (benchmarks.nonEmpty) visit(benchmarkClass)
    benchmarks.foreach { m =>
      if (m.isOneOf(Private | Protected | Abstract) || m.privateWithin.exists)
        fail("Benchmark methods must be public and concrete", m)
      if (
        annotation(m, "Group").nonEmpty || annotation(
          m,
          "GroupThreads"
        ).nonEmpty
      )
        fail("Benchmark groups are not supported by Native JMH yet", m)
      stateArguments(m).foreach(visit)
    }
    def id(cls: ClassSymbol): Int = states.indexOf(cls)
    def reachable(method: Symbol): List[ClassSymbol] = {
      def walk(cls: ClassSymbol): List[ClassSymbol] =
        dependencies(cls).flatMap(walk) ::: List(cls)
      (walk(benchmarkClass) ::: stateArguments(method).flatMap(walk)).distinct
    }

    val suiteType = requiredClassRef(runtime + "Suite")
    val environmentType = requiredClassRef(runtime + "Environment")
    val controlType = requiredClassRef(runtime + "IterationControl")
    val rawType = requiredClassRef(runtime + "RawResult")
    val module = newCompleteModuleSymbol(
      benchmarkClass.owner,
      (className(benchmarkClass) + suffix).toTermName,
      Synthetic,
      Synthetic,
      List(defn.ObjectType, suiteType),
      newScope,
      coord = benchmarkClass.span
    ).entered
    val owner = module.moduleClass.asClass
    dotty.tools.dotc.sbt.APIUtils.registerDummyClass(owner)
    def literal(value: Any): Tree = Literal(value match {
      case v: String  => Constant(v)
      case v: Int     => Constant(v)
      case v: Boolean => Constant(v)
      case v: Long    => Constant(v)
      case _          =>
        throw new IllegalArgumentException(s"Unsupported literal $value")
    })
    def call(target: Tree, name: String, args: Tree*): Tree =
      target
        .select(target.tpe.widen.classSymbol.requiredMethod(name))
        .appliedToArgs(args.toList)
    def state(env: Tree, cls: ClassSymbol): Tree =
      call(env, "state", literal(id(cls))).cast(cls.typeRef)
    def injected(env: Tree, tpe: Type): Tree =
      tpe.classSymbol.fullName.toString match {
        case "org.openjdk.jmh.infra.Blackhole" =>
          env.select(environmentType.classSymbol.requiredMethod("blackhole"))
        case "org.openjdk.jmh.infra.ThreadParams" =>
          env.select(environmentType.classSymbol.requiredMethod("threadParams"))
        case _ => state(env, tpe.classSymbol.asClass)
      }
    def invoke(target: Tree, method: Symbol, env: Tree): Tree = {
      def applyLists(tree: Tree, tpe: Type): Tree = tpe match {
        case mt: MethodType =>
          applyLists(
            tree.appliedToArgs(mt.paramInfos.map(injected(env, _))),
            mt.resType
          )
        case _ => tree
      }
      applyLists(target.select(method), method.info)
    }
    def method(
        name: String,
        names: List[String],
        types: List[Type],
        result: Type
    )(body: List[Tree] => Tree): DefDef = {
      val symbol = newSymbol(
        owner,
        name.toTermName,
        Synthetic | Method | Override,
        MethodType(names.map(_.toTermName), types, result)
      ).entered
      DefDef(symbol.asTerm, args => body(args.flatten))
    }
    def choose(selector: Tree, cases: List[(Int, Tree)], default: Tree): Tree =
      cases.foldRight(default) { case ((n, body), next) =>
        If(selector.select(defn.Int_==).appliedTo(literal(n)), body, next)
      }
    def helpers(
        cls: ClassSymbol,
        isSetup: Boolean,
        lvl: Int,
        target: Tree,
        env: Tree
    ): Tree =
      Block(
        fixtures(cls, isSetup)
          .filter(level(_, isSetup) == lvl)
          .map(invoke(target, _, env)),
        unitLiteral
      )

    val metadata = benchmarks.map { benchmark =>
      val props = mutable.Map.empty[String, String]
      def put(key: String, v: String): Unit = props(key) = v
      def config(name: String): Option[Annotation] =
        annotation(benchmark, name).orElse(inherited(benchmarkClass, name))
      put(
        "name",
        benchmarkClass.fullName.toString + "." + benchmark.name.toString
      )
      val modes = config("BenchmarkMode")
        .flatMap(a => arguments(a).get("value"))
        .map(enums)
        .getOrElse(List("Throughput"))
      put(
        "modes",
        (if (modes.contains("All"))
           List("Throughput", "AverageTime", "SampleTime", "SingleShotTime")
         else modes).mkString(",")
      )
      def number(
          ann: String,
          arg: String,
          key: String,
          allowZero: Boolean = false
      ): Unit = {
        val v = value(config(ann), arg, "-1").toInt
        if (v != -1) {
          if (v < (if (allowZero) 0 else 1))
            fail(s"Invalid $ann.$arg: $v", benchmark)
          put(key, v.toString)
        }
      }
      def duration(ann: String, key: String): Unit = {
        val time = value(config(ann), "time", "-1").toLong
        if (time != -1) {
          if (time <= 0) fail(s"$ann.time must be positive", benchmark)
          val unit = java.util.concurrent.TimeUnit
            .valueOf(value(config(ann), "timeUnit", "SECONDS"))
          put(key, unit.toNanos(time).toString)
        }
      }
      number("Warmup", "iterations", "warmupIterations", true)
      number("Measurement", "iterations", "measurementIterations")
      number("Warmup", "batchSize", "warmupBatchSize")
      number("Measurement", "batchSize", "measurementBatchSize")
      duration("Warmup", "warmupNanos");
      duration("Measurement", "measurementNanos");
      duration("Timeout", "timeoutNanos")
      number("Fork", "value", "forks", true);
      number("Fork", "warmups", "warmupForks", true)
      val threads = value(config("Threads"), "value", "1")
      put("threads", threads)
      number("OperationsPerInvocation", "value", "operationsPerInvocation")
      put("unit", value(config("OutputTimeUnit"), "value", "SECONDS"))
      val used = reachable(benchmark)
      put("states", used.map(id).mkString(","))
      used.foreach { cls =>
        val pre = s"state.${id(cls)}."
        put(pre + "scope", value(inherited(cls, "State"), "value", "Thread"))
        put(pre + "dependencies", dependencies(cls).map(id).mkString(","))
        val params = fields(cls)
        put(pre + "params", params.map(_.name.toString).distinct.mkString(","))
        params.foreach { field =>
          if (inherited(cls, "State").isEmpty)
            fail("@Param requires @State", field)
          val values = arguments(annotation(field, "Param").get)
            .get("value")
            .map(strings)
            .getOrElse(Nil)
          val actual =
            if (values.isEmpty || values == List("blank_blank_blank_2014")) {
              val enumClass = field.info.classSymbol
              if (!enumClass.is(Enum))
                fail(
                  "@Param requires explicit values unless its type is an enum",
                  field
                )
              enumClass.companionModule.info.decls
                .filter(_.is(EnumVal))
                .map(_.name.toString)
                .toList
            } else values
          put(pre + s"param.${field.name}.count", actual.size.toString)
          actual.zipWithIndex.foreach { case (v, i) =>
            put(pre + s"param.${field.name}.$i", v)
          }
        }
      }
      val ignored = config("Fork").toList
        .flatMap(arguments)
        .filter { case (k, _) => k.startsWith("jvm") }
        .flatMap { case (k, v) =>
          val literalValues = strings(v)
          val vs =
            (if (literalValues.nonEmpty) literalValues
             else constant(v).toList).filterNot(_ == "blank_blank_blank_2014")
          if (vs.nonEmpty)
            List(s"Ignored JVM-only Fork.$k=${vs.mkString(",")}")
          else Nil
        }
      put("warnings", ignored.size.toString)
      ignored.zipWithIndex.foreach { case (warning, i) =>
        put(s"warning.$i", warning);
        report.warning(warning, benchmark.sourcePos)
      }
      def escape(v: String): String = v.flatMap {
        case '\\'                     => "\\\\"
        case '\n'                     => "\\n"
        case '\r'                     => "\\r"
        case '\t'                     => "\\t"
        case c if " =:#!".contains(c) => "\\" + c
        case c                        => c.toString
      }
      props.toList
        .sortBy(_._1)
        .map { case (k, v) => escape(k) + "=" + escape(v) }
        .mkString("\n")
    }
    val metadataDef =
      method("metadata", Nil, Nil, defn.ArrayOf(defn.StringType)) { _ =>
        JavaSeqLiteral(metadata.map(literal), TypeTree(defn.StringType))
      }
    val instantiate = method(
      "instantiate",
      List("state"),
      List(defn.IntType),
      defn.ObjectType
    ) { args =>
      choose(
        args.head,
        states.toList.map(cls => id(cls) -> New(cls.typeRef, Nil)),
        nullLiteral
      )
    }
    val assign = method(
      "assign",
      List("state", "target", "parameter", "value"),
      List(defn.IntType, defn.ObjectType, defn.StringType, defn.StringType),
      defn.UnitType
    ) { args =>
      val List(which, target, parameter, text) = args: @unchecked
      val cases = states.toList.map { cls =>
        val assignments = fields(cls).map { field =>
          val bridge = field.owner.info
            .member(bridgeName(field))
            .symbol
          if (!bridge.exists)
            fail(
              "Compile the defining @Param state with the Native JMH plugin",
              field
            )
          val typeName = field.info.classSymbol.fullName.toString
          val conversion = Map(
            "scala.Boolean" -> "boolean",
            "java.lang.Boolean" -> "boolean",
            "scala.Byte" -> "byte",
            "java.lang.Byte" -> "byte",
            "scala.Short" -> "short",
            "java.lang.Short" -> "short",
            "scala.Char" -> "char",
            "java.lang.Character" -> "char",
            "scala.Int" -> "int",
            "java.lang.Integer" -> "int",
            "scala.Long" -> "long",
            "java.lang.Long" -> "long",
            "scala.Float" -> "float",
            "java.lang.Float" -> "float",
            "scala.Double" -> "double",
            "java.lang.Double" -> "double",
            "java.lang.String" -> "string"
          )
          val parsed = conversion.get(typeName) match {
            case Some(parser) =>
              ref(requiredModule(runtime + "Values"))
                .select(
                  requiredModule(runtime + "Values").requiredMethod(parser)
                )
                .appliedTo(text)
            case None if field.info.classSymbol.is(Enum) =>
              val companion = field.info.classSymbol.companionModule
              ref(companion)
                .select(companion.requiredMethod("valueOf"))
                .appliedTo(text)
            case _ => fail(s"Unsupported @Param type $typeName", field)
          }
          val equal = parameter
            .select(defn.Any_==)
            .appliedTo(literal(field.name.toString))
          If(
            equal,
            target
              .cast(field.owner.asClass.typeRef)
              .select(bridge)
              .appliedTo(parsed),
            unitLiteral
          )
        }
        id(cls) -> Block(assignments, unitLiteral)
      }
      choose(which, cases, unitLiteral)
    }
    def fixtureDef(setup: Boolean): DefDef = method(
      if (setup) "setup" else "teardown",
      List("state", "level", "target", "environment"),
      List(defn.IntType, defn.IntType, defn.ObjectType, environmentType),
      defn.UnitType
    ) { args =>
      val List(which, lvl, target, env) = args: @unchecked
      choose(
        which,
        states.toList.map { cls =>
          id(cls) -> choose(
            lvl,
            (0 to 2).toList.map(n => n -> helpers(cls, setup, n, target.cast(cls.typeRef), env)),
            unitLiteral
          )
        },
        unitLiteral
      )
    }
    val measure = method(
      "measure",
      List("benchmark", "environment", "control"),
      List(defn.IntType, environmentType, controlType),
      rawType
    ) { args =>
      val List(which, env, control) = args: @unchecked
      val cases = benchmarks.zipWithIndex.map { case (benchmark, index) =>
        val invocation = invoke(state(env, benchmarkClass), benchmark, env)
        val consumed =
          if (benchmark.info.finalResultType =:= defn.UnitType) invocation
          else {
            val blackhole = injected(
              env,
              requiredClassRef("org.openjdk.jmh.infra.Blackhole")
            )
            val overloads = blackhole.tpe.classSymbol.info
              .member("consume".toTermName)
              .alternatives
            val consumer = overloads
              .find(_.info.paramInfoss.flatten.head =:= invocation.tpe.widen)
              .getOrElse(
                overloads
                  .find(_.info.paramInfoss.flatten.head =:= defn.ObjectType)
                  .get
              )
            blackhole.select(consumer.symbol).appliedTo(invocation)
          }
        val used = reachable(benchmark)
        val hasInvocation = used.exists(cls =>
          (fixtures(cls, true) ::: fixtures(cls, false)).exists(m =>
            annotation(m, "Setup")
              .map(_ => level(m, true))
              .getOrElse(level(m, false)) == 2
          )
        )
        def inlineFixtures(setup: Boolean): Tree = {
          val order = if (setup) used else used.reverse
          Block(
            order
              .filter(cls => fixtures(cls, setup).exists(level(_, setup) == 2))
              .map { cls =>
                env
                  .select(
                    environmentType.classSymbol.requiredMethod("guarded")
                  )
                  .appliedToArgs(
                    List(literal(id(cls)), literal(2), literal(setup))
                  )
                  .appliedTo(helpers(cls, setup, 2, state(env, cls), env))
              },
            unitLiteral
          )
        }
        val loops = requiredModule(runtime + "Loops")
        index -> ref(loops)
          .select(loops.requiredMethod("run"))
          .appliedToArgs(
            List(
              control,
              env.select(
                environmentType.classSymbol.requiredMethod("sampling")
              ),
              literal(hasInvocation),
              consumed,
              inlineFixtures(true),
              inlineFixtures(false)
            )
          )
      }
      choose(
        which,
        cases,
        Throw(New(requiredClassRef("java.lang.IllegalArgumentException"), Nil))
      )
    }
    measure.symbol.addAnnotation(requiredClass("scala.noinline"))
    // Avoid a parameter-specialized copy of the outer harness boundary.
    measure.symbol.addAnnotation(
      requiredClass("scala.scalanative.annotation.nospecialize")
    )
    ModuleDef(
      module,
      List(
        metadataDef,
        instantiate,
        assign,
        fixtureDef(true),
        fixtureDef(false),
        measure
      )
    )
  }
}
