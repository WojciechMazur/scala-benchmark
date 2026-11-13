package scala.scalanative.jmh.plugin

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Annotations.Annotation
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Decorators.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.plugins.PluginPhase
import dotty.tools.dotc.{report, transform}

/** Compiler-side representation deliberately has no runtime dependency. */
private[plugin] object PlatformEligibility {
  val all = List("JVM", "Native", "JS", "Wasm")
  case class Restriction(allowed: List[String], reasons: List[String]) {
    def supports(native: Boolean): Boolean =
      if (native) allowed.contains("Native") else allowed.exists(Set("JS", "Wasm"))
  }
  private def declared(symbol: Symbol)(using Context): List[Restriction] =
    symbol.annotations.filter(_.symbol.fullName.toString == "scala.benchmark.Platforms").map { annotation =>
      val names = annotation.symbol.primaryConstructor.paramSymss.flatten.map(_.name.toString)
      val args = annotation.arguments.zipWithIndex.map {
        case (NamedArg(name, value), _) => name.toString -> value
        case (value, index)             => names.lift(index).getOrElse("") -> value
      }.toMap
      def platform(tree: Tree): Option[String] = tree match {
        case Typed(value, _)      => platform(value)
        case Inlined(_, _, value) => platform(value)
        case _
            if tree.symbol.exists && all.contains(tree.symbol.name.toString) &&
              tree.symbol.fullName.toString.replace("$", "").startsWith("scala.benchmark.Platform.") =>
          Some(tree.symbol.name.toString)
        case _ => None
      }
      def arrayElements(tree: Tree): Option[List[Tree]] = tree match {
        case SeqLiteral(elements, _) => Some(elements)
        case Typed(value, _)         => arrayElements(value)
        case Inlined(_, _, value)    => arrayElements(value)
        case Apply(fun, args)
            if fun.symbol.exists &&
              fun.symbol.fullName.toString.replace("$", "") == "scala.Array.apply" =>
          args.iterator.flatMap(arrayElements).take(1).toList.headOption.orElse(arrayElements(fun))
        case TypeApply(fun, _) => arrayElements(fun)
        case _                 => None
      }
      val elements = args.get("value").flatMap(arrayElements)
      val values = elements.toList.flatten.flatMap(platform)
      val allowed = if (elements.exists(_.size == values.size)) values.distinct else Nil
      if (allowed.isEmpty)
        report.error("@Platforms requires a nonempty literal Array of Platform values", symbol.sourcePos)
      val reason = args.get("reason").toList.flatMap {
        case Literal(c) if c.value.isInstanceOf[String]             => Option(c.stringValue).filter(_.nonEmpty)
        case tree if tree.symbol.name.toString.contains("default$") => Nil
        case tree                                                   =>
          tree.tpe.widenTermRefExpr match {
            case ConstantType(c) if c.value.isInstanceOf[String] => Option(c.stringValue).filter(_.nonEmpty)
            case _                                               =>
              report.error("@Platforms reason must be a constant string", symbol.sourcePos)
              Nil
          }
      }
      Restriction(allowed, reason)
    }

  def effective(cls: ClassSymbol, method: Option[Symbol] = None)(using Context): Restriction = {
    val restrictions = cls.baseClasses.flatMap(declared) ::: method.toList.flatMap { m =>
      (m :: m.allOverriddenSymbols.toList).flatMap(declared)
    }
    Restriction(
      restrictions.foldLeft(all)((allowed, r) => allowed.filter(r.allowed.contains)),
      restrictions.flatMap(_.reasons).distinct
    )
  }

  def properties(cls: ClassSymbol, method: Symbol)(using Context): List[(String, String)] = {
    val restriction = effective(cls, Some(method))
    List("platforms" -> restriction.allowed.mkString(","), "platformReasons" -> restriction.reasons.size.toString) :::
      restriction.reasons.zipWithIndex.map { case (reason, i) => s"platformReason.$i" -> reason }
  }
}

/** Validate even declarations for which no executable harness is generated. */
final class PlatformEligibility extends PluginPhase {
  val phaseName = "benchmark-platform-eligibility"
  override val runsAfter = Set(transform.PostTyper.name, "benchmark-facade-annotations")
  override val runsBefore = Set(transform.Pickler.name)

  private def validate(cls: ClassSymbol, method: Option[Symbol])(using Context): Unit = {
    val restriction = PlatformEligibility.effective(cls, method)
    if (restriction.allowed.isEmpty)
      report.error(
        "Empty effective @Platforms intersection" +
          (if (restriction.reasons.isEmpty) "" else ": " + restriction.reasons.mkString("; ")),
        method.getOrElse(cls).sourcePos
      )
  }
  override def transformTypeDef(tree: TypeDef)(using Context): Tree = {
    if (tree.symbol.isClass) validate(tree.symbol.asClass, None)
    tree
  }
  override def transformDefDef(tree: DefDef)(using Context): Tree = {
    if (tree.symbol.owner.isClass && !tree.symbol.isConstructor)
      validate(tree.symbol.owner.asClass, Some(tree.symbol))
    tree
  }
  override def transformValDef(tree: ValDef)(using Context): Tree = {
    if (tree.symbol.annotations.exists(_.symbol.fullName.toString == "scala.benchmark.Platforms"))
      report.error("@Platforms applies to benchmark classes and methods", tree.sourcePos)
    tree
  }
}
