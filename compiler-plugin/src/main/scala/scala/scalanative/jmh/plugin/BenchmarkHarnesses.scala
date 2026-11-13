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

/** Expands the loop templates before Scala inlining. Every benchmark call and invocation fixture becomes part of the
  * generated loop, with no callback. The Native backend subsequently processes these trees like ordinary Scala.
  */
final class BenchmarkHarnesses(nativeBackend: Boolean = true, jvmBackend: Boolean = false) extends PluginPhase {
  val phaseName = "scalanative-jmh-harnesses"
  override val runsAfter =
    Set(transform.PostTyper.name, "benchmark-facade-annotations", "benchmark-platform-eligibility")
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
    // Retain annotated declarations hidden by overrides: JMH emits each
    // declaration's helper call, with normal virtual dispatch at the call site.
    val all = symbol.baseClasses
      .flatMap(_.info.decls.toList)
      .filter(s => s.is(Method) && annotation(s, if (setup) "Setup" else "TearDown").nonEmpty)
      .toList
      .distinct
    // JMH 1.37's reflection generator orders helpers by qualified method name
    // for both setup and teardown (RFMethodInfo.compareTo).
    all.sortBy(s => (s.owner.fullName.toString, s.name.toString))
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
    "BREAK",
    "OPERATIONS",
    "EVENTS"
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
      case pt: PolyType   => loop(pt.instantiate(pt.paramInfos.map(_.hi)))
      case _              => Nil
    }
    loop(method.info)
  }
  private def addControl(symbol: Symbol, control: String)(using Context): Unit =
    control match {
      case "DONT_INLINE" =>
        symbol.addAnnotation(requiredClass("scala.noinline"))
      case "INLINE" =>
        symbol.addAnnotation(
          requiredClass(if (nativeBackend) "scala.scalanative.annotation.alwaysinline" else "scala.inline")
        )
      case other =>
        fail(
          s"CompilerControl.$other has no supported equivalent on this backend",
          symbol
        )
    }

  override def transformDefDef(tree: DefDef)(using Context): Tree = {
    try {
      if (
        jvmBackend || (tree.symbol.owner.isClass &&
          !PlatformEligibility.effective(tree.symbol.owner.asClass, Some(tree.symbol)).supports(nativeBackend))
      )
        return tree
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
    val inventory =
      (if (benchmarkClass.isOneOf(ModuleClass | Abstract | Trait)) Nil
       else methods(benchmarkClass))
        .filter(m => (m :: m.allOverriddenSymbols.toList).exists(annotation(_, "Benchmark").nonEmpty))
        .sortBy(_.name.toString)
    inventory.foreach { method =>
      val restriction = PlatformEligibility.effective(benchmarkClass, Some(method))
      if (restriction.allowed.isEmpty)
        fail(
          "Empty effective @Platforms intersection for " + benchmarkClass.fullName.toString + "." + method.name.toString +
            (if (restriction.reasons.isEmpty) "" else ": " + restriction.reasons.mkString("; ")),
          benchmarkClass
        )
    }
    def groupName(method: Symbol): String = value(annotation(method, "Group"), "value", method.name.toString)
    val groups = inventory.groupBy(groupName)
    def restrictions(method: Symbol): PlatformEligibility.Restriction = {
      val entries = groups(groupName(method)).map(m => PlatformEligibility.effective(benchmarkClass, Some(m)))
      PlatformEligibility.Restriction(
        entries.foldLeft(PlatformEligibility.all)((allowed, r) => allowed.filter(r.allowed.contains)),
        entries.flatMap(_.reasons).distinct
      )
    }
    groups.values.foreach { members =>
      if (restrictions(members.head).allowed.isEmpty)
        fail("Empty effective @Platforms intersection for group " + groupName(members.head), benchmarkClass)
    }
    val benchmarks = inventory.filter(m => !jvmBackend && restrictions(m).supports(nativeBackend))
    benchmarks.groupBy(groupName).values.foreach { members =>
      if (members.map(_.name).distinct.size != members.size)
        fail("Duplicate @Benchmark method name within a group", benchmarkClass)
    }
    if (jvmBackend) return generateJvmInventory(benchmarkClass, inventory)
    val stateClasses = mutable.ArrayBuffer.empty[ClassSymbol]
    val dependencies = mutable.Map.empty[ClassSymbol, List[ClassSymbol]]
    val active = mutable.Set.empty[ClassSymbol]
    val auxMembers = mutable.Map.empty[ClassSymbol, List[Symbol]]
    val numeric = Set(
      "scala.Byte",
      "scala.Short",
      "scala.Int",
      "scala.Long",
      "scala.Float",
      "scala.Double",
      "java.lang.Byte",
      "java.lang.Short",
      "java.lang.Integer",
      "java.lang.Long",
      "java.lang.Float",
      "java.lang.Double"
    )
    def counterKind(cls: ClassSymbol): String = value(inherited(cls, "AuxCounters"), "value", "OPERATIONS")
    val special = Set(
      "org.openjdk.jmh.infra.Blackhole",
      "org.openjdk.jmh.infra.ThreadParams",
      "org.openjdk.jmh.infra.BenchmarkParams",
      "org.openjdk.jmh.infra.IterationParams",
      "org.openjdk.jmh.infra.Control"
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
            "Arguments must be @State classes or JMH infrastructure (Blackhole, *Params, Control)",
            method
          )
      }
    def visit(cls: ClassSymbol): Unit = {
      if (active(cls)) fail("Cycle in @State dependencies", cls)
      if (!stateClasses.contains(cls)) {
        if (
          cls.isOneOf(Abstract | Trait | ModuleClass) ||
          !(cls.owner.is(Package) || cls.owner.is(ModuleClass))
        )
          fail("Benchmark states must be concrete classes at package or object scope", cls)
        if (!cls.info.decls.exists(s => s.isConstructor && parameters(s).isEmpty && !s.is(Private)))
          fail("Benchmark states require a public no-argument constructor", cls)
        val scope = value(inherited(cls, "State"), "value", "Thread")
        if (inherited(cls, "AuxCounters").nonEmpty) {
          if (scope != "Thread") fail("@AuxCounters requires Scope.Thread", cls)
          val members = cls.info.decls
            .filter(s =>
              s.isTerm && !s.isConstructor && !s.isOneOf(Private | Protected) &&
                !s.privateWithin.exists && (!s.is(Method) || !(s.info.finalResultType =:= defn.UnitType))
            )
            .toList
          members.foreach { member =>
            if (!numeric(member.info.finalResultType.classSymbol.fullName.toString))
              fail("Public auxiliary counters must have numeric types", member)
            if (member.is(Method) && parameters(member).nonEmpty)
              fail("Auxiliary counter methods must have no arguments", member)
          }
          auxMembers(cls) = members.sortBy(_.name.toString)
        }
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
        def rank(scope: String): Int = scope match {
          case "Benchmark" => 0
          case "Group"     => 1
          case _           => 2
        }
        if (deps.exists(dep => rank(scope) < rank(value(inherited(dep, "State"), "value", "Thread"))))
          fail("A state cannot depend on a state with a narrower sharing scope", cls)
        dependencies(cls) = deps
        deps.foreach(visit)
        active -= cls
        stateClasses += cls
      }
    }
    if (benchmarks.nonEmpty) visit(benchmarkClass)
    benchmarks.foreach { m =>
      if (m.isOneOf(Private | Protected | Abstract) || m.privateWithin.exists)
        fail("Benchmark methods must be public and concrete", m)
      if (annotation(m, "Group").nonEmpty && annotation(m, "Threads").nonEmpty)
        fail("@Threads is ambiguous on a grouped method; use @GroupThreads", m)
      if (annotation(m, "GroupThreads").nonEmpty && annotation(m, "Group").isEmpty)
        fail("@GroupThreads requires @Group", m)
      stateArguments(m).foreach(visit)
    }
    // JMH allocates Thread state per injection site. Benchmark/Group states
    // collapse by class, with runtime workers applying their sharing scope.
    case class StateInstance(cls: ClassSymbol, id: Int, arguments: Map[Symbol, List[Int]]) {
      def dependencies: List[Int] = arguments.values.flatten.toList.distinct.sorted
    }
    val instances = mutable.ArrayBuffer.empty[StateInstance]
    val sharedInstances = mutable.Map.empty[ClassSymbol, Int]
    def instantiateState(cls: ClassSymbol): Int = sharedInstances.getOrElse(
      cls, {
        val arguments = (fixtures(cls, true) ::: fixtures(cls, false)).distinct.map { helper =>
          helper -> stateArguments(helper).map(instantiateState)
        }.toMap
        val id = instances.size
        instances += StateInstance(cls, id, arguments)
        if (value(inherited(cls, "State"), "value", "Thread") != "Thread") sharedInstances(cls) = id
        id
      }
    )
    val holders = benchmarks.map(m => m -> instantiateState(benchmarkClass)).toMap
    val benchmarkArguments = benchmarks.map(m => m -> stateArguments(m).map(instantiateState)).toMap
    def reachable(method: Symbol): List[Int] = {
      def walk(id: Int): List[Int] = instances(id).dependencies.flatMap(walk) ::: List(id)
      (walk(holders(method)) ::: benchmarkArguments(method).flatMap(walk)).distinct
    }
    def counters(method: Symbol): List[(Int, Symbol)] = {
      val members = reachable(method).flatMap(id => auxMembers.getOrElse(instances(id).cls, Nil).map(id -> _))
      if (members.map(_._2.name.toString).distinct.size != members.size)
        fail("Conflicting auxiliary counter names injected into benchmark", method)
      members
    }
    val suiteType = requiredClassRef(
      if (jvmBackend) "scala.benchmark.internal.BenchmarkInventory" else runtime + "Suite"
    )
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
      case v: Double  => Constant(v)
      case _          =>
        throw new IllegalArgumentException(s"Unsupported literal $value")
    })
    def call(target: Tree, name: String, args: Tree*): Tree =
      target
        .select(target.tpe.widen.classSymbol.requiredMethod(name))
        .appliedToArgs(args.toList)
    def state(env: Tree, id: Int): Tree =
      call(env, "state", literal(id)).cast(instances(id).cls.typeRef)
    def injected(env: Tree, tpe: Type, stateId: => Int): Tree =
      tpe.classSymbol.fullName.toString match {
        case "org.openjdk.jmh.infra.Blackhole" =>
          env.select(environmentType.classSymbol.requiredMethod("blackhole"))
        case "org.openjdk.jmh.infra.ThreadParams" =>
          env.select(environmentType.classSymbol.requiredMethod("threadParams"))
        case "org.openjdk.jmh.infra.BenchmarkParams" =>
          env.select(environmentType.classSymbol.requiredMethod("benchmarkParams"))
        case "org.openjdk.jmh.infra.IterationParams" =>
          env.select(environmentType.classSymbol.requiredMethod("iterationParams"))
        case "org.openjdk.jmh.infra.Control" =>
          env.select(environmentType.classSymbol.requiredMethod("notifyControl"))
        case _ => state(env, stateId)
      }
    def invoke(target: Tree, method: Symbol, env: Tree, arguments: List[Int] = Nil): Tree = {
      val states = arguments.iterator
      def applyLists(tree: Tree, tpe: Type): Tree = tpe match {
        case pt: PolyType =>
          val types = pt.paramInfos.map(_.hi)
          applyLists(tree.appliedToTypes(types), pt.instantiate(types))
        case mt: MethodType =>
          applyLists(
            tree.appliedToArgs(mt.paramInfos.map(tpe => injected(env, tpe, states.next()))),
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
        stateId: Int,
        isSetup: Boolean,
        lvl: Int,
        target: Tree,
        env: Tree
    ): Tree =
      Block(
        fixtures(instances(stateId).cls, isSetup)
          .filter(level(_, isSetup) == lvl)
          .map(m => invoke(target, m, env, instances(stateId).arguments(m))),
        unitLiteral
      )

    val entries = groups.toList.sortBy(_._1).map(_._2.head)
    val metadata = entries.map { benchmark =>
      val props = mutable.Map.empty[String, String]
      def put(key: String, v: String): Unit = props(key) = v
      val groupMembers = groups(groupName(benchmark))
      val annotationCache = mutable.Map.empty[String, List[Annotation]]
      def groupAnnotations(name: String): List[Annotation] = annotationCache.getOrElseUpdate(
        name, {
          def signature(ann: Annotation): Map[String, List[String]] =
            arguments(ann).flatMap { case (key, tree) =>
              val values = constant(tree).toList match {
                case Nil    => strings(tree)
                case values => values
              }
              val blank = values.isEmpty || values == List("blank_blank_blank_2014") ||
                (Set("Warmup", "Measurement", "Fork")(name) && values == List("-1")) ||
                (key == "timeUnit" && values == List("SECONDS"))
              if (blank) None else Some(key -> values)
            }
          val inheritedAnnotations = benchmarkClass.baseClasses.filterNot(_.is(Trait)).flatMap(annotation(_, name))
          var result = List.empty[Annotation]
          groupMembers.foreach { method =>
            val annotations = annotation(method, name).toList ::: inheritedAnnotations
            if (result.nonEmpty && annotations.nonEmpty && result.map(signature) != annotations.map(signature))
              fail(s"Colliding $name annotations in group ${groupName(benchmark)}", method)
            result = annotations
          }
          result
        }
      )
      def config(name: String): Option[Annotation] = groupAnnotations(name).headOption
      def groupValue(name: String, argument: String, default: String): String =
        groupAnnotations(name).iterator
          .map(ann => value(Some(ann), argument, default))
          .find { v =>
            v != "blank_blank_blank_2014" &&
            !(Set("Warmup", "Measurement", "Fork")(name) && v == "-1")
          }
          .getOrElse(default)
      put(
        "name",
        benchmarkClass.fullName.toString + "." + value(annotation(benchmark, "Group"), "value", benchmark.name.toString)
      )
      put("generatedTarget", benchmarkClass.fullName.toString + suffix + ".measure")
      val restriction = restrictions(benchmark)
      put("platforms", restriction.allowed.mkString(","))
      put("platformReasons", restriction.reasons.size.toString)
      restriction.reasons.zipWithIndex.foreach { case (reason, i) => put(s"platformReason.$i", reason) }
      val declaredModes = groupMembers
        .flatMap(m =>
          annotation(m, "BenchmarkMode")
            .orElse(inherited(benchmarkClass, "BenchmarkMode"))
            .flatMap(a => arguments(a).get("value"))
            .toList
            .flatMap(enums)
        )
        .distinct
      val modes = if (declaredModes.isEmpty) List("Throughput") else declaredModes
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
        val v = groupValue(ann, arg, "-1").toInt
        if (v != -1) {
          if (v < (if (allowZero) 0 else 1))
            fail(s"Invalid $ann.$arg: $v", benchmark)
          put(key, v.toString)
        }
      }
      def duration(ann: String, key: String): Unit = {
        val declared = groupAnnotations(ann).find(a => value(Some(a), "time", "-1") != "-1")
        declared.foreach { annotation =>
          val time = value(Some(annotation), "time", "-1").toLong
          if (time < 0) fail(s"$ann.time must be nonnegative", benchmark)
          val unit = java.util.concurrent.TimeUnit.valueOf(value(Some(annotation), "timeUnit", "SECONDS"))
          put(key, unit.toNanos(time).toString)
          val prefix = if (ann == "Warmup") "warmup" else if (ann == "Measurement") "measurement" else "timeout"
          put(prefix + "TimeValue", time.toString)
          put(prefix + "TimeUnit", unit.name())
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
      val threads = groupValue("Threads", "value", "1")
      put("threads", threads)
      number("OperationsPerInvocation", "value", "operationsPerInvocation")
      put("unit", groupValue("OutputTimeUnit", "value", "SECONDS"))
      val members = groups(groupName(benchmark))
      val grouped = members.exists(annotation(_, "Group").nonEmpty)
      val eligible = benchmarks.contains(benchmark)
      val used = if (eligible) members.flatMap(reachable).distinct else Nil
      if (
        eligible && !grouped && used
          .exists(id => value(inherited(instances(id).cls, "State"), "value", "Thread") == "Group")
      )
        fail("Scope.Group requires a grouped benchmark", benchmark)
      if (grouped) {
        put("members", members.size.toString)
        members.zipWithIndex.foreach { case (member, index) =>
          val threads = value(annotation(member, "GroupThreads"), "value", "1").toInt
          if (threads < 0) fail("GroupThreads must be nonnegative", member)
          put(s"member.$index.name", member.name.toString)
          put(s"member.$index.threads", threads.toString)
          put(s"member.$index.states", (if (eligible) reachable(member) else Nil).mkString(","))
        }
      }
      put("states", used.mkString(","))
      used.foreach { stateId =>
        val cls = instances(stateId).cls
        val pre = s"state.$stateId."
        put(pre + "scope", value(inherited(cls, "State"), "value", "Thread"))
        put(pre + "dependencies", instances(stateId).dependencies.mkString(","))
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
          if (eligible) actual.foreach { text =>
            val valid = try {
              field.info.classSymbol.fullName.toString match {
                case "scala.Boolean" | "java.lang.Boolean" => text == "true" || text == "false"
                case "scala.Char" | "java.lang.Character"  => text.length == 1
                case "scala.Byte" | "java.lang.Byte"       => java.lang.Byte.parseByte(text); true
                case "scala.Short" | "java.lang.Short"     => java.lang.Short.parseShort(text); true
                case "scala.Int" | "java.lang.Integer"     => java.lang.Integer.parseInt(text); true
                case "scala.Long" | "java.lang.Long"       => java.lang.Long.parseLong(text); true
                case "scala.Float" | "java.lang.Float"     => java.lang.Float.parseFloat(text); true
                case "scala.Double" | "java.lang.Double"   => java.lang.Double.parseDouble(text); true
                case _                                     => true
              }
            } catch { case _: NumberFormatException => false }
            if (!valid) fail(s"Invalid @Param value '$text' for ${field.name}", field)
          }
          put(pre + s"param.${field.name}.count", actual.size.toString)
          actual.zipWithIndex.foreach { case (v, i) =>
            put(pre + s"param.${field.name}.$i", v)
          }
        }
      }
      val counterEntries = if (eligible) members.zipWithIndex.flatMap { case (method, member) =>
        counters(method).map { case (cls, counter) => (counter.name.toString, counterKind(instances(cls).cls), member) }
      }
      else Nil
      put("counters", counterEntries.size.toString)
      counterEntries.zipWithIndex.foreach { case ((name, kind, member), i) =>
        put(s"counter.$i.name", name)
        put(s"counter.$i.kind", kind)
        put(s"counter.$i.member", member.toString)
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
        instances.toList.map(i => i.id -> New(i.cls.typeRef, Nil)),
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
      val cases = instances.toList.map { instance =>
        val cls = instance.cls
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
            "scala.Boolean" -> "paramBoolean",
            "java.lang.Boolean" -> "paramBoolean",
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
        instance.id -> Block(assignments, unitLiteral)
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
        instances.toList.map { instance =>
          val cls = instance.cls
          instance.id -> choose(
            lvl,
            (0 to 2).toList.map(n => n -> helpers(instance.id, setup, n, target.cast(cls.typeRef), env)),
            unitLiteral
          )
        },
        unitLiteral
      )
    }
    def auxiliaryMethod(reset: Boolean): DefDef = method(
      if (reset) "resetAuxiliary" else "auxiliary",
      List("benchmark", "environment"),
      List(defn.IntType, environmentType),
      if (reset) defn.UnitType else defn.ArrayOf(defn.DoubleType)
    ) { args =>
      val List(which, env) = args: @unchecked
      val cases = entries.zipWithIndex.filter(p => benchmarks.contains(p._1)).map { case (head, index) =>
        val alternatives = groups(groupName(head)).zipWithIndex.map { case (benchmark, subgroup) =>
          val values = counters(benchmark).flatMap { case (cls, counter) =>
            val accessor =
              if (counter.is(Method)) invoke(state(env, cls), counter, env) else state(env, cls).select(counter)
            if (reset) {
              // Scala vars become accessor methods on JVM; JMH does not reset
              // them as Java public fields. Keep the shared-source behavior.
              if (counter.is(Method) || !instances(cls).cls.is(JavaDefined)) Nil
              else {
                val tpe = counter.info.classSymbol.fullName.toString
                val values = requiredModule(runtime + "Values")
                val zero =
                  if (tpe.startsWith("java.lang."))
                    call(ref(values), "zero", literal(tpe)).cast(counter.info)
                  else call(ref(values), tpe.stripPrefix("scala.").toLowerCase, literal("0"))
                List(Assign(accessor, zero))
              }
            } else
              List(
                If(
                  call(env, "counterEnabled", literal(counterKind(instances(cls).cls))),
                  call(ref(requiredModule(runtime + "Values")), "counter", accessor),
                  literal(0.0)
                )
              )
          }
          subgroup -> (if (reset) Block(values, unitLiteral) else JavaSeqLiteral(values, TypeTree(defn.DoubleType)))
        }
        val subgroup = call(env.select(environmentType.classSymbol.requiredMethod("threadParams")), "getSubgroupIndex")
        index -> choose(
          subgroup,
          alternatives,
          if (reset) unitLiteral else JavaSeqLiteral(Nil, TypeTree(defn.DoubleType))
        )
      }
      choose(which, cases, if (reset) unitLiteral else JavaSeqLiteral(Nil, TypeTree(defn.DoubleType)))
    }
    val measure = method(
      "measure",
      List("benchmark", "environment", "control"),
      List(defn.IntType, environmentType, controlType),
      rawType
    ) { args =>
      val List(which, env, control) = args: @unchecked
      val cases = benchmarks.map { benchmark =>
        val index = inventory.indexOf(benchmark)
        val invocation = invoke(state(env, holders(benchmark)), benchmark, env, benchmarkArguments(benchmark))
        val consumed =
          if (benchmark.info.finalResultType =:= defn.UnitType) invocation
          else {
            val blackhole = injected(
              env,
              requiredClassRef("org.openjdk.jmh.infra.Blackhole"),
              -1
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
        val hasInvocation = used.exists(id =>
          (fixtures(instances(id).cls, true) ::: fixtures(instances(id).cls, false)).exists(m =>
            annotation(m, "Setup")
              .map(_ => level(m, true))
              .getOrElse(level(m, false)) == 2
          )
        )
        def inlineFixtures(setup: Boolean): Tree = {
          val order = if (setup) used else used.reverse
          Block(
            order
              .filter(id => fixtures(instances(id).cls, setup).exists(level(_, setup) == 2))
              .map { stateId =>
                env
                  .select(
                    environmentType.classSymbol.requiredMethod("guarded")
                  )
                  .appliedToArgs(
                    List(literal(stateId), literal(2), literal(setup))
                  )
                  .appliedTo(helpers(stateId, setup, 2, state(env, stateId), env))
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
              inlineFixtures(false),
              env.select(environmentType.classSymbol.requiredMethod("notifyControl"))
            )
          )
      }
      val byIndex = cases.toMap
      val dispatched = groups.toList.sortBy(_._1).flatMap { case (_, members) =>
        val eligible = members.filter(benchmarks.contains)
        eligible.headOption.map { head =>
          val subgroup =
            call(env.select(environmentType.classSymbol.requiredMethod("threadParams")), "getSubgroupIndex")
          entries.indexOf(head) -> choose(
            subgroup,
            eligible.zipWithIndex.map { case (member, i) => i -> byIndex(inventory.indexOf(member)) },
            Throw(New(requiredClassRef("java.lang.IllegalArgumentException"), Nil))
          )
        }
      }
      choose(
        which,
        dispatched,
        Throw(New(requiredClassRef("java.lang.IllegalArgumentException"), Nil))
      )
    }
    measure.symbol.addAnnotation(requiredClass("scala.noinline"))
    // Avoid a parameter-specialized copy of the outer harness boundary.
    if (nativeBackend)
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
        auxiliaryMethod(true),
        auxiliaryMethod(false),
        measure
      )
    )
  }
  private def generateJvmInventory(cls: ClassSymbol, benchmarks: List[Symbol])(using Context): Tree = {
    val module = newCompleteModuleSymbol(
      cls.owner,
      (className(cls) + suffix).toTermName,
      Synthetic,
      Synthetic,
      List(defn.ObjectType, requiredClassRef("scala.benchmark.internal.BenchmarkInventory")),
      newScope,
      coord = cls.span
    ).entered
    val owner = module.moduleClass.asClass
    dotty.tools.dotc.sbt.APIUtils.registerDummyClass(owner)
    val metadata =
      newSymbol(owner, "metadata".toTermName, Method, MethodType(Nil, Nil, defn.ArrayOf(defn.StringType))).entered
    def escape(v: String): String = v.flatMap {
      case '\\'                     => "\\\\"
      case '\n'                     => "\\n"
      case '\r'                     => "\\r"
      case '\t'                     => "\\t"
      case c if " =:#!".contains(c) => "\\" + c
      case c                        => c.toString
    }
    val entries =
      benchmarks.groupBy(m => value(annotation(m, "Group"), "value", m.name.toString)).toList.sortBy(_._1).map {
        case (name, members) =>
          val restrictions = members.map(m => PlatformEligibility.effective(cls, Some(m)))
          val allowed =
            restrictions.foldLeft(PlatformEligibility.all)((allowed, r) => allowed.filter(r.allowed.contains))
          val reasons = restrictions.flatMap(_.reasons).distinct
          val props = List(
            "name" -> (cls.fullName.toString + "." + name),
            "platforms" -> allowed.mkString(","),
            "platformReasons" -> reasons.size.toString
          ) :::
            reasons.zipWithIndex.map { case (reason, i) => s"platformReason.$i" -> reason }
          Literal(Constant(props.map { case (k, v) => escape(k) + "=" + escape(v) }.mkString("\n")))
      }
    ModuleDef(module, List(DefDef(metadata.asTerm, _ => JavaSeqLiteral(entries, TypeTree(defn.StringType)))))
  }

}
