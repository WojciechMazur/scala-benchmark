package scala.scalanative.jmh.plugin

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Decorators.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.plugins.PluginPhase
import dotty.tools.dotc.transform

/** Older JVM backends do not follow exported Java enum constants in annotation trees. Restore the original enum field
  * before pickling and bytecode emission.
  */
final class FacadeAnnotations extends PluginPhase {
  val phaseName = "benchmark-facade-annotations"
  override val runsAfter = Set(transform.PostTyper.name)
  override val runsBefore = Set(transform.Pickler.name)

  private def normalize(symbol: Symbol)(using Context): Unit = {
    if (symbol.exists) {
      val mapper = new TreeMap {
        override def transform(tree: Tree)(using Context): Tree = {
          val cls = tree.tpe.widenTermRefExpr.classSymbol
          if (
            tree.symbol.exists &&
            tree.symbol.fullName.toString.startsWith("scala.benchmark.") &&
            cls.exists && cls.isAllOf(JavaDefined | Enum) &&
            cls.fullName.toString.startsWith("org.openjdk.jmh.annotations.")
          )
            ref(cls.companionModule).select(tree.symbol.name.asTermName)
          else super.transform(tree)
        }
      }
      symbol.denot.annotations = symbol.annotations.map { annotation =>
        if (annotation.symbol.fullName.toString.startsWith("org.openjdk.jmh.annotations."))
          annotation.derivedAnnotation(mapper.transform(annotation.tree))
        else annotation
      }
    }
  }

  override def transformTypeDef(tree: TypeDef)(using Context): Tree = { normalize(tree.symbol); tree }
  override def transformDefDef(tree: DefDef)(using Context): Tree = { normalize(tree.symbol); tree }
  override def transformValDef(tree: ValDef)(using Context): Tree = { normalize(tree.symbol); tree }
}
