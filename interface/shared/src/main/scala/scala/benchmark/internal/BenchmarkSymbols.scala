package scala.benchmark.internal

import scala.quoted.*

/** Annotation queries shared by the inventory macro and the Native/JS harness macro.
  *
  * Benchmark sources are never rewritten: everything here only reads the JMH annotations that the same sources expose
  * to upstream JMH on the JVM.
  */
class BenchmarkSymbols[Q <: Quotes](using val quotes: Q):
  import quotes.reflect.*

  final case class Restriction(allowed: List[String], reasons: List[String])

  val allPlatforms: List[String] = List("JVM", "Native", "JS", "Wasm")

  private val annotations = "org.openjdk.jmh.annotations."
  private val platformsAnnotation = "scala.benchmark.Platforms"

  /** Enum constants are read by name: their trees differ between Java (JVM) and Scala (Native/JS) definitions. */
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

  private val modeNames = Set("Throughput", "AverageTime", "SampleTime", "SingleShotTime", "All")

  def fail(message: String, symbol: Symbol): Nothing =
    report.errorAndAbort(s"$message [${symbol.fullName}]", symbol.pos.getOrElse(Position.ofMacroExpansion))

  def annotation(symbol: Symbol, name: String): Option[Term] =
    symbol.annotations.find(_.tpe.typeSymbol.fullName == annotations + name)

  def inherited(cls: Symbol, name: String): Option[Term] =
    cls.typeRef.baseClasses.iterator.flatMap(annotation(_, name)).nextOption()

  def overridden(method: Symbol): List[Symbol] = method :: method.allOverriddenSymbols.toList

  def isRestricted(symbol: Symbol): Boolean =
    symbol.flags.is(Flags.Private) || symbol.flags.is(Flags.Protected) || symbol.privateWithin.nonEmpty

  def arguments(annotation: Term): Map[String, Term] =
    val names = annotation.tpe.typeSymbol.primaryConstructor.paramSymss.flatten.filter(_.isTerm).map(_.name)
    def applied(term: Term): List[Term] = term match
      case Apply(function, values) => applied(function) ::: values
      case _                       => Nil
    applied(annotation).zipWithIndex.map {
      case (NamedArg(name, value), _) => name -> value
      case (value, index)             => names.lift(index).getOrElse(index.toString) -> value
    }.toMap

  private def isDefault(term: Term): Boolean = term.symbol.name.contains("default$")

  /** A reference to a `val` whose right-hand side is available, as in `@Param(Sizes)`. */
  private def binding(term: Term): Option[Term] =
    val symbol = term.symbol
    if symbol.isNoSymbol || enumNames(symbol.name) then None
    else
      try
        symbol.tree match
          case ValDef(_, _, Some(rhs)) if rhs.symbol != symbol => Some(rhs)
          case _                                               => None
      catch case _: Exception => None

  def constant(term: Term): Option[String] = term match
    case Literal(value)                     => Option(value.value).map(_.toString)
    case NamedArg(_, value)                 => constant(value)
    case Typed(value, _)                    => constant(value)
    case Inlined(_, _, value)               => constant(value)
    case _: Ident if binding(term).nonEmpty => binding(term).flatMap(constant)
    case _ if !term.symbol.isNoSymbol && !isDefault(term) =>
      term.tpe.widenTermRefByName match
        case ConstantType(value) => Some(value.value.toString)
        case _                   => Option.when(enumNames(term.symbol.name))(term.symbol.name)
    case _ => None

  def strings(term: Term): List[String] = term match
    case Literal(StringConstant(value)) => List(value)
    case Repeated(elements, _)          => elements.flatMap(strings)
    case Apply(function, values)        => strings(function) ::: values.flatMap(strings)
    case TypeApply(function, _)         => strings(function)
    case Typed(value, _)                => strings(value)
    case Inlined(_, _, value)           => strings(value)
    case _: Ident                       => binding(term).toList.flatMap(strings)
    case _                              => Nil

  def modes(term: Term): List[String] = term match
    case Repeated(elements, _)              => elements.flatMap(modes)
    case Apply(function, values)            => modes(function) ::: values.flatMap(modes)
    case TypeApply(function, _)             => modes(function)
    case Typed(value, _)                    => modes(value)
    case Inlined(_, _, value)               => modes(value)
    case _: Ident if binding(term).nonEmpty => binding(term).toList.flatMap(modes)
    case _                                  => constant(term).toList.filter(modeNames)

  def value(annotation: Option[Term], name: String, default: String): String =
    annotation.flatMap(arguments(_).get(name)).flatMap(constant).getOrElse(default)

  def groupName(method: Symbol): String = value(annotation(method, "Group"), "value", method.name)

  /** Concrete benchmark methods of a class, including inherited ones, ordered by name. */
  def benchmarks(cls: Symbol): List[Symbol] =
    val flags = cls.flags
    if flags.is(Flags.Module) || flags.is(Flags.Abstract) || flags.is(Flags.Trait) then Nil
    else
      cls.methodMembers
        .filter(method => !method.isClassConstructor && overridden(method).exists(annotation(_, "Benchmark").nonEmpty))
        .distinct
        .sortBy(_.name)

  private def declaredRestrictions(symbol: Symbol): List[Restriction] =
    symbol.annotations.filter(_.tpe.typeSymbol.fullName == platformsAnnotation).map { annotation =>
      val args = arguments(annotation)
      def platform(term: Term): Option[String] = term match
        case Typed(value, _)      => platform(value)
        case Inlined(_, _, value) => platform(value)
        case _ =>
          val symbol = term.symbol
          Option.when(
            !symbol.isNoSymbol && allPlatforms.contains(symbol.name) &&
              symbol.fullName.replace("$", "").startsWith("scala.benchmark.Platform.")
          )(symbol.name)
      def elements(term: Term): Option[List[Term]] = term match
        case Repeated(values, _)  => Some(values)
        case Typed(value, _)      => elements(value)
        case Inlined(_, _, value) => elements(value)
        case Apply(function, values) if function.symbol.fullName.replace("$", "") == "scala.Array.apply" =>
          values.iterator.flatMap(elements).nextOption().orElse(elements(function))
        case TypeApply(function, _) => elements(function)
        case _                      => None
      val declared = args.get("value").flatMap(elements)
      val platforms = declared.toList.flatten.flatMap(platform)
      val allowed = if declared.exists(_.size == platforms.size) then platforms.distinct else Nil
      if allowed.isEmpty then fail("@Platforms requires a nonempty literal Array of Platform values", symbol)
      val reasons = args.get("reason").toList.flatMap {
        case Literal(StringConstant(text)) => Option(text).filter(_.nonEmpty)
        case term if isDefault(term)       => None
        case term =>
          term.tpe.widenTermRefByName match
            case ConstantType(StringConstant(text)) => Option(text).filter(_.nonEmpty)
            case _                                  => fail("@Platforms reason must be a constant string", symbol)
      }
      Restriction(allowed, reasons)
    }

  private def intersect(restrictions: List[Restriction]): Restriction =
    Restriction(
      restrictions.foldLeft(allPlatforms)((allowed, restriction) => allowed.filter(restriction.allowed.contains)),
      restrictions.flatMap(_.reasons).distinct
    )

  /** Class, inherited and method restrictions are intersected. */
  def restriction(cls: Symbol, method: Symbol): Restriction =
    val result =
      intersect(cls.typeRef.baseClasses.flatMap(declaredRestrictions) ::: overridden(method).flatMap(declaredRestrictions))
    if result.allowed.isEmpty then fail("Empty effective @Platforms intersection" + describe(result.reasons), method)
    result

  /** All members of a group run together, so the group is eligible only where every member is. */
  def groupRestriction(cls: Symbol, members: List[Symbol]): Restriction =
    val result = intersect(members.map(restriction(cls, _)))
    if result.allowed.isEmpty then
      fail(s"Empty effective @Platforms intersection for group ${groupName(members.head)}", cls)
    result

  def validatePlatformTargets(cls: Symbol): Unit =
    cls.declaredFields
      .filter(_.annotations.exists(_.tpe.typeSymbol.fullName == platformsAnnotation))
      .foreach(fail("@Platforms applies to benchmark classes and methods", _))

  private def describe(reasons: List[String]): String =
    if reasons.isEmpty then "" else reasons.mkString(": ", "; ", "")
