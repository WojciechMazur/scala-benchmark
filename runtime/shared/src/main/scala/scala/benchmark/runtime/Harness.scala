package scala.benchmark.runtime

import java.util.concurrent.TimeUnit

import scala.benchmark.internal.BenchmarkSymbols
import scala.collection.mutable
import scala.quoted.*

import org.openjdk.jmh.annotations.{Scope as _, *}
import org.openjdk.jmh.infra.{BenchmarkParams, Blackhole, Control, IterationParams, ThreadParams}

/** Derives the typed harness of one benchmark class.
  *
  * The benchmark call and its invocation fixtures are spliced into the inline [[Loops]] templates, so the measured loop
  * contains no callback. State wiring, fixture ordering and validation follow JMH 1.37.
  */
object Harness:
  inline def derive[T]: Suite = ${ deriveImpl[T] }

  private def deriveImpl[T: Type](using Quotes): Expr[Suite] = new HarnessMacro(quotes).suite[T]

final private class HarnessMacro[Q <: Quotes](q: Q) extends BenchmarkSymbols[Q](using q):
  import quotes.reflect.*

  private val blank = "blank_blank_blank_2014"
  private val trial = 0
  private val invocation = 2

  private def symbol[A: Type]: Symbol = TypeRepr.of[A].typeSymbol

  /** JMH infrastructure that may be injected next to `@State` arguments. */
  private val infrastructure: Map[Symbol, Expr[Environment] => Expr[Any]] = Map(
    symbol[Blackhole] -> (environment => '{ $environment.blackhole }),
    symbol[ThreadParams] -> (environment => '{ $environment.threadParams }),
    symbol[BenchmarkParams] -> (environment => '{ $environment.benchmarkParams }),
    symbol[IterationParams] -> (environment => '{ $environment.iterationParams }),
    symbol[Control] -> (environment => '{ $environment.notifyControl })
  )

  /** How a `@Param` text is checked at compile time and converted at run time. */
  final private case class ParameterType(valid: String => Boolean, parse: Expr[String] => Expr[Any])

  private def parses(parse: String => Any): String => Boolean = text =>
    try
      parse(text); true
    catch case _: NumberFormatException => false

  private val parameterTypes: Map[Symbol, ParameterType] =
    val boolean = (text: String) => text == "true" || text == "false"
    val char = (text: String) => text.length == 1
    Map(
      symbol[Boolean] -> ParameterType(boolean, text => '{ Values.paramBoolean($text) }),
      symbol[java.lang.Boolean] -> ParameterType(boolean, text => '{ Boolean.box(Values.paramBoolean($text)) }),
      symbol[Char] -> ParameterType(char, text => '{ Values.char($text) }),
      symbol[java.lang.Character] -> ParameterType(char, text => '{ Char.box(Values.char($text)) }),
      symbol[Byte] -> ParameterType(parses(_.toByte), text => '{ Values.byte($text) }),
      symbol[java.lang.Byte] -> ParameterType(parses(_.toByte), text => '{ Byte.box(Values.byte($text)) }),
      symbol[Short] -> ParameterType(parses(_.toShort), text => '{ Values.short($text) }),
      symbol[java.lang.Short] -> ParameterType(parses(_.toShort), text => '{ Short.box(Values.short($text)) }),
      symbol[Int] -> ParameterType(parses(_.toInt), text => '{ Values.int($text) }),
      symbol[java.lang.Integer] -> ParameterType(parses(_.toInt), text => '{ Int.box(Values.int($text)) }),
      symbol[Long] -> ParameterType(parses(_.toLong), text => '{ Values.long($text) }),
      symbol[java.lang.Long] -> ParameterType(parses(_.toLong), text => '{ Long.box(Values.long($text)) }),
      symbol[Float] -> ParameterType(parses(_.toFloat), text => '{ Values.float($text) }),
      symbol[java.lang.Float] -> ParameterType(parses(_.toFloat), text => '{ Float.box(Values.float($text)) }),
      symbol[Double] -> ParameterType(parses(_.toDouble), text => '{ Values.double($text) }),
      symbol[java.lang.Double] -> ParameterType(parses(_.toDouble), text => '{ Double.box(Values.double($text)) }),
      symbol[String] -> ParameterType(_ => true, identity)
    )

  /** Auxiliary counter types, with the value Java-defined counter fields are reset to. */
  private val counterZeros: Map[Symbol, Expr[Any]] = Map(
    symbol[Byte] -> '{ 0.toByte },
    symbol[java.lang.Byte] -> '{ Byte.box(0.toByte) },
    symbol[Short] -> '{ 0.toShort },
    symbol[java.lang.Short] -> '{ Short.box(0.toShort) },
    symbol[Int] -> '{ 0 },
    symbol[java.lang.Integer] -> '{ Int.box(0) },
    symbol[Long] -> '{ 0L },
    symbol[java.lang.Long] -> '{ Long.box(0L) },
    symbol[Float] -> '{ 0.0f },
    symbol[java.lang.Float] -> '{ Float.box(0.0f) },
    symbol[Double] -> '{ 0.0 },
    symbol[java.lang.Double] -> '{ Double.box(0.0) }
  )

  // ---------------------------------------------------------------------------------------------------------------
  // Symbol queries
  // ---------------------------------------------------------------------------------------------------------------

  private def signature(method: Symbol): TypeRepr = method.owner.typeRef.memberType(method)

  private def parameterTypes(method: Symbol): List[TypeRepr] =
    def loop(tpe: TypeRepr): List[TypeRepr] = tpe match
      case method: MethodType => method.paramTypes ::: loop(method.resType)
      case poly: PolyType     => loop(poly.resType)
      case _                  => Nil
    loop(signature(method))

  private def resultType(member: Symbol): TypeRepr =
    def loop(tpe: TypeRepr): TypeRepr = tpe match
      case method: MethodType => loop(method.resType)
      case poly: PolyType     => loop(poly.resType)
      case byName: ByNameType => loop(byName.underlying)
      case other              => other
    loop(signature(member))

  private def returnsUnit(member: Symbol): Boolean = resultType(member) =:= TypeRepr.of[Unit]

  private def scope(cls: Symbol): String = value(inherited[State](cls), "value", "Thread")

  private def parameterFields(cls: Symbol): List[Symbol] =
    cls.typeRef.baseClasses.reverse.flatMap(_.declaredFields.filter(annotation[Param](_).nonEmpty)).distinct

  /** Annotated declarations hidden by overrides are retained: JMH emits each declaration's helper call, with normal
    * virtual dispatch at the call site. JMH 1.37 orders helpers by qualified method name (RFMethodInfo.compareTo).
    */
  private def fixtures(cls: Symbol, setup: Boolean): List[Symbol] =
    cls.typeRef.baseClasses
      .flatMap(_.declaredMethods)
      .filter(fixtureAnnotation(_, setup).nonEmpty)
      .distinct
      .sortBy(method => (method.owner.fullName, method.name))

  private def fixtureAnnotation(method: Symbol, setup: Boolean): Option[Term] =
    if setup then annotation[Setup](method) else annotation[TearDown](method)

  private def allFixtures(cls: Symbol): List[Symbol] = (fixtures(cls, true) ::: fixtures(cls, false)).distinct

  private def level(method: Symbol, setup: Boolean): Int =
    value(fixtureAnnotation(method, setup), "value", "Trial") match
      case "Trial"      => 0
      case "Iteration"  => 1
      case "Invocation" => 2
      case other        => fail(s"Unknown fixture level $other", method)

  private def stateArguments(method: Symbol): List[Symbol] =
    parameterTypes(method).flatMap { tpe =>
      tpe.classSymbol match
        case Some(cls) if infrastructure.contains(cls)   => Nil
        case Some(cls) if inherited[State](cls).nonEmpty => List(cls)
        case _ => fail("Arguments must be @State classes or JMH infrastructure (Blackhole, *Params, Control)", method)
    }

  // ---------------------------------------------------------------------------------------------------------------
  // Model
  // ---------------------------------------------------------------------------------------------------------------

  /** JMH allocates Thread state per injection site. Benchmark/Group states collapse by class, with runtime workers
    * applying their sharing scope.
    */
  final private case class StateInstance(cls: Symbol, id: Int, arguments: Map[Symbol, List[Int]]):
    def dependencies: List[Int] = arguments.values.flatten.toList.distinct.sorted

  final private class Model(val benchmarkClass: Symbol):
    /** Every benchmark of the class, on any platform. Indices into this list identify members at runtime. */
    val inventory: List[Symbol] = benchmarks(benchmarkClass)
    val groups: Map[String, List[Symbol]] = inventory.groupBy(groupName)

    /** One representative per group, in metadata order. */
    val entries: List[Symbol] = groups.toList.sortBy(_._1).map(_._2.head)

    val restrictions: Map[String, Restriction] =
      groups.map((name, members) => name -> groupRestriction(benchmarkClass, members))

    /** Benchmarks for which this platform generates code. */
    val eligible: List[Symbol] = inventory.filter(method => supported(restrictions(groupName(method))))

    private val states = mutable.ArrayBuffer.empty[Symbol]
    private val active = mutable.Set.empty[Symbol]
    val auxiliaryMembers = mutable.Map.empty[Symbol, List[Symbol]]

    eligible.groupBy(groupName).values.foreach { members =>
      if members.map(_.name).distinct.size != members.size then
        fail("Duplicate @Benchmark method name within a group", benchmarkClass)
    }

    if eligible.nonEmpty then visit(benchmarkClass)

    eligible.foreach { method =>
      if isRestricted(method) || method.flags.is(Flags.Deferred) then
        fail("Benchmark methods must be public and concrete", method)
      if annotation[Group](method).nonEmpty && annotation[Threads](method).nonEmpty then
        fail("@Threads is ambiguous on a grouped method; use @GroupThreads", method)
      if annotation[GroupThreads](method).nonEmpty && annotation[Group](method).isEmpty then
        fail("@GroupThreads requires @Group", method)
      stateArguments(method).foreach(visit)
    }

    // A macro cannot change how other methods are compiled, unlike JMH's compiler directives on the JVM.
    for
      cls <- (benchmarkClass :: states.toList).distinct
      method <- cls.declaredMethods
      if annotation[CompilerControl](method).nonEmpty && !inventory.contains(method)
    do
      report.warning(
        s"@CompilerControl on ${method.name} only takes effect on the JVM; " +
          "add @noinline or @inline for Scala Native and Scala.js",
        method.pos.getOrElse(Position.ofMacroExpansion)
      )

    val instances = mutable.ArrayBuffer.empty[StateInstance]
    private val shared = mutable.Map.empty[Symbol, Int]
    val holders: Map[Symbol, Int] = eligible.map(method => method -> instantiate(benchmarkClass)).toMap

    val benchmarkArguments: Map[Symbol, List[Int]] =
      eligible.map(method => method -> stateArguments(method).map(instantiate)).toMap

    private def supported(restriction: Restriction): Boolean =
      restriction.allowed.exists(HarnessPlatform.names)

    private def rank(scope: String): Int = scope match
      case "Benchmark" => 0
      case "Group"     => 1
      case _           => 2

    private def visit(cls: Symbol): Unit =
      if active(cls) then fail("Cycle in @State dependencies", cls)
      if !states.contains(cls) then
        val flags = cls.flags
        val owner = cls.owner.flags
        if flags.is(Flags.Abstract) || flags.is(Flags.Trait) || flags.is(Flags.Module) ||
          !(owner.is(Flags.Package) || owner.is(Flags.Module))
        then fail("Benchmark states must be concrete classes at package or object scope", cls)
        if defaultConstructor(cls).isEmpty then fail("Benchmark states require a public no-argument constructor", cls)
        if inherited[AuxCounters](cls).nonEmpty then auxiliaryMembers(cls) = auxiliaryCounters(cls)
        active += cls
        val helpers = allFixtures(cls)
        helpers.foreach { method =>
          if isRestricted(method) || !returnsUnit(method) then
            fail("Fixture methods must be public and return Unit", method)
        }
        val dependencies = helpers.flatMap(stateArguments).distinct
        if dependencies.exists(dependency => rank(scope(cls)) < rank(scope(dependency))) then
          fail("A state cannot depend on a state with a narrower sharing scope", cls)
        dependencies.foreach(visit)
        active -= cls
        states += cls

    private def auxiliaryCounters(cls: Symbol): List[Symbol] =
      if scope(cls) != "Thread" then fail("@AuxCounters requires Scope.Thread", cls)
      val members = (cls.declaredFields ::: cls.declaredMethods).filter { member =>
        !member.isClassConstructor && !isRestricted(member) && (!member.isDefDef || !returnsUnit(member))
      }
      members.foreach { member =>
        if !resultType(member).classSymbol.exists(counterZeros.contains) then
          fail("Public auxiliary counters must have numeric types", member)
        if member.isDefDef && parameterTypes(member).nonEmpty then
          fail("Auxiliary counter methods must have no arguments", member)
      }
      members.sortBy(_.name)

    private def instantiate(cls: Symbol): Int = shared.getOrElse(
      cls, {
        val arguments = allFixtures(cls).map(helper => helper -> stateArguments(helper).map(instantiate)).toMap
        val id = instances.size
        instances += StateInstance(cls, id, arguments)
        if scope(cls) != "Thread" then shared(cls) = id
        id
      }
    )

    def reachable(method: Symbol): List[Int] =
      def walk(id: Int): List[Int] = instances(id).dependencies.flatMap(walk) ::: List(id)
      (walk(holders(method)) ::: benchmarkArguments(method).flatMap(walk)).distinct

    def counters(method: Symbol): List[(Int, Symbol)] =
      val members = reachable(method).flatMap(id => auxiliaryMembers.getOrElse(instances(id).cls, Nil).map(id -> _))
      if members.map(_._2.name).distinct.size != members.size then
        fail("Conflicting auxiliary counter names injected into benchmark", method)
      members

    def counterKind(id: Int): String = value(inherited[AuxCounters](instances(id).cls), "value", "OPERATIONS")
  end Model

  private def defaultConstructor(cls: Symbol): Option[Symbol] =
    cls.declarations.find { member =>
      member.isClassConstructor && member.paramSymss.flatten.forall(_.isType) && !member.flags.is(Flags.Private)
    }

  // ---------------------------------------------------------------------------------------------------------------
  // Metadata
  // ---------------------------------------------------------------------------------------------------------------

  private def escape(text: String): String = text.flatMap {
    case '\\'                     => "\\\\"
    case '\n'                     => "\\n"
    case '\r'                     => "\\r"
    case '\t'                     => "\\t"
    case c if " =:#!".contains(c) => "\\" + c
    case c                        => c.toString
  }

  /** Annotations whose numeric members use -1 for "not declared". */
  private val blankNumbers = Set(symbol[Warmup], symbol[Measurement], symbol[Fork])

  private def isBlank(annotation: Symbol, text: String): Boolean =
    text == blank || (blankNumbers(annotation) && text == "-1")

  private def metadata(model: Model, benchmark: Symbol): String =
    import model.*
    val properties = mutable.Map.empty[String, String]
    def put(key: String, text: String): Unit = properties(key) = text
    val group = groupName(benchmark)
    val members = groups(group)
    val cache = mutable.Map.empty[Symbol, List[Term]]

    /** Members of one group must agree on every configuration annotation they declare, as in JMH. */
    def groupAnnotations[A: Type]: List[Term] = cache.getOrElseUpdate(
      symbol[A], {
        val name = symbol[A]
        def declared(annotation: Term): Map[String, List[String]] =
          arguments(annotation).flatMap { (key, term) =>
            val values = constant(term).toList match
              case Nil    => strings(term)
              case values => values
            val unset = values.isEmpty || values.forall(isBlank(name, _)) && values.size == 1 ||
              (key == "timeUnit" && values == List("SECONDS"))
            Option.unless(unset)(key -> values)
          }
        val inheritedAnnotations =
          benchmarkClass.typeRef.baseClasses.filterNot(_.flags.is(Flags.Trait)).flatMap(annotation[A](_))
        var result = List.empty[Term]
        members.foreach { method =>
          val annotations = annotation[A](method).toList ::: inheritedAnnotations
          if result.nonEmpty && annotations.nonEmpty && result.map(declared) != annotations.map(declared) then
            fail(s"Colliding ${name.name} annotations in group $group", method)
          result = annotations
        }
        result
      }
    )
    def groupValue[A: Type](argument: String, default: String): String =
      groupAnnotations[A].iterator
        .map(annotation => value(Some(annotation), argument, default))
        .find(!isBlank(symbol[A], _))
        .getOrElse(default)
    def number[A: Type](argument: String, key: String, allowZero: Boolean = false): Unit =
      val declared = groupValue[A](argument, "-1").toInt
      if declared != -1 then
        if declared < (if allowZero then 0 else 1) then
          fail(s"Invalid ${symbol[A].name}.$argument: $declared", benchmark)
        put(key, declared.toString)
    def duration[A: Type](prefix: String): Unit =
      groupAnnotations[A].find(annotation => value(Some(annotation), "time", "-1") != "-1").foreach { annotation =>
        val time = value(Some(annotation), "time", "-1").toLong
        if time < 0 then fail(s"${symbol[A].name}.time must be nonnegative", benchmark)
        val unit = TimeUnit.valueOf(value(Some(annotation), "timeUnit", "SECONDS"))
        put(prefix + "Nanos", unit.toNanos(time).toString)
        put(prefix + "TimeValue", time.toString)
        put(prefix + "TimeUnit", unit.name())
      }

    put("name", benchmarkClass.fullName + "." + group)
    put("generatedTarget", benchmarkClass.fullName + ".measure")
    val restriction = restrictions(group)
    put("platforms", restriction.allowed.mkString(","))
    put("platformReasons", restriction.reasons.size.toString)
    restriction.reasons.zipWithIndex.foreach((reason, index) => put(s"platformReason.$index", reason))

    val declaredModes = members
      .flatMap { member =>
        annotation[BenchmarkMode](member)
          .orElse(inherited[BenchmarkMode](benchmarkClass))
          .flatMap(arguments(_).get("value"))
          .toList
          .flatMap(modes)
      }
      .distinct
    val selectedModes = if declaredModes.isEmpty then List("Throughput") else declaredModes
    put(
      "modes",
      (if selectedModes.contains("All") then List("Throughput", "AverageTime", "SampleTime", "SingleShotTime")
       else selectedModes).mkString(",")
    )
    number[Warmup]("iterations", "warmupIterations", allowZero = true)
    number[Measurement]("iterations", "measurementIterations")
    number[Warmup]("batchSize", "warmupBatchSize")
    number[Measurement]("batchSize", "measurementBatchSize")
    duration[Warmup]("warmup")
    duration[Measurement]("measurement")
    duration[Timeout]("timeout")
    number[Fork]("value", "forks", allowZero = true)
    number[Fork]("warmups", "warmupForks", allowZero = true)
    put("threads", groupValue[Threads]("value", "1"))
    number[OperationsPerInvocation]("value", "operationsPerInvocation")
    put("unit", groupValue[OutputTimeUnit]("value", "SECONDS"))

    val grouped = members.exists(annotation[Group](_).nonEmpty)
    val generated = eligible.contains(benchmark)
    val used = if generated then members.flatMap(reachable).distinct else Nil
    if generated && !grouped && used.exists(id => scope(instances(id).cls) == "Group") then
      fail("Scope.Group requires a grouped benchmark", benchmark)
    if grouped then
      put("members", members.size.toString)
      members.zipWithIndex.foreach { (member, index) =>
        val threads = value(annotation[GroupThreads](member), "value", "1").toInt
        if threads < 0 then fail("GroupThreads must be nonnegative", member)
        put(s"member.$index.name", member.name)
        put(s"member.$index.threads", threads.toString)
        put(s"member.$index.states", (if generated then reachable(member) else Nil).mkString(","))
      }
    put("states", used.mkString(","))
    used.foreach { id =>
      val cls = instances(id).cls
      val prefix = s"state.$id."
      put(prefix + "scope", scope(cls))
      put(prefix + "dependencies", instances(id).dependencies.mkString(","))
      val parameters = parameterFields(cls)
      put(prefix + "params", parameters.map(_.name).distinct.mkString(","))
      parameters.foreach { field =>
        if inherited[State](cls).isEmpty then fail("@Param requires @State", field)
        val values = parameterValues(field)
        values.foreach { text =>
          if !parameterType(field).forall(_.valid(text)) then
            fail(s"Invalid @Param value '$text' for ${field.name}", field)
        }
        put(prefix + s"param.${field.name}.count", values.size.toString)
        values.zipWithIndex.foreach((text, index) => put(prefix + s"param.${field.name}.$index", text))
      }
    }

    val counterEntries =
      if !generated then Nil
      else
        members.zipWithIndex.flatMap { (method, member) =>
          counters(method).map((id, counter) => (counter.name, counterKind(id), member))
        }
    put("counters", counterEntries.size.toString)
    counterEntries.zipWithIndex.foreach { case ((name, kind, member), index) =>
      put(s"counter.$index.name", name)
      put(s"counter.$index.kind", kind)
      put(s"counter.$index.member", member.toString)
    }

    val ignored = groupAnnotations[Fork].headOption.toList.flatMap(arguments).filter(_._1.startsWith("jvm")).flatMap {
      (key, term) =>
        val literals = strings(term)
        val values = (if literals.nonEmpty then literals else constant(term).toList).filterNot(_ == blank)
        Option.when(values.nonEmpty)(s"Ignored JVM-only Fork.$key=${values.mkString(",")}")
    }
    put("warnings", ignored.size.toString)
    ignored.zipWithIndex.foreach { (warning, index) =>
      put(s"warning.$index", warning)
      report.warning(warning, benchmark.pos.getOrElse(Position.ofMacroExpansion))
    }
    properties.toList.sortBy(_._1).map((key, text) => escape(key) + "=" + escape(text)).mkString("\n")
  end metadata

  private def fieldType(field: Symbol): TypeRepr = signature(field).widen

  private def parameterValues(field: Symbol): List[String] =
    val declared = arguments(annotation[Param](field).get).get("value").map(strings).getOrElse(Nil)
    if declared.nonEmpty && declared != List(blank) then declared
    else
      val enumClass = fieldType(field).classSymbol.filter(_.flags.is(Flags.Enum)).getOrElse {
        fail("@Param requires explicit values unless its type is an enum", field)
      }
      enumClass.companionModule.moduleClass.declaredFields
        .filter(member => member.flags.is(Flags.Enum) && member.flags.is(Flags.StableRealizable))
        .map(_.name)

  private def parameterType(field: Symbol): Option[ParameterType] =
    fieldType(field).classSymbol.flatMap(parameterTypes.get)

  // ---------------------------------------------------------------------------------------------------------------
  // Code generation
  // ---------------------------------------------------------------------------------------------------------------

  private def choose[A: Type](selector: Expr[Int], cases: List[(Int, Expr[A])], default: Expr[A]): Expr[A] =
    cases.foldRight(default) { case ((index, body), next) =>
      '{ if $selector == ${ Expr(index) } then $body else $next }
    }

  private def sequence(statements: List[Term]): Expr[Unit] = Block(statements, '{ () }.asTerm).asExprOf[Unit]

  private def cast(target: Expr[AnyRef], cls: Symbol): Term =
    cls.typeRef.asType match
      case '[t] => '{ $target.asInstanceOf[t] }.asTerm

  final private class Generator(model: Model):
    import model.*

    def state(environment: Expr[Environment], id: Int): Term =
      cast('{ $environment.state(${ Expr(id) }) }, instances(id).cls)

    private def injected(environment: Expr[Environment], tpe: TypeRepr, states: Iterator[Int]): Term =
      tpe.classSymbol.flatMap(infrastructure.get) match
        case Some(inject) => inject(environment).asTerm
        case None         => state(environment, states.next())

    /** Applies every parameter list; type parameters are instantiated to their upper bounds, as after erasure. */
    def invoke(target: Term, method: Symbol, environment: Expr[Environment], states: List[Int] = Nil): Term =
      val remaining = states.iterator
      def applyLists(function: Term): Term = function.tpe.widen match
        case poly: PolyType     => applyLists(function.appliedToTypes(poly.paramBounds.map(_.hi)))
        case method: MethodType =>
          applyLists(function.appliedToArgs(method.paramTypes.map(injected(environment, _, remaining))))
        case _ => function
      applyLists(target.select(method))

    def helpers(id: Int, setup: Boolean, level: Int, target: Term, environment: Expr[Environment]): Expr[Unit] =
      val instance = instances(id)
      sequence(
        fixtures(instance.cls, setup)
          .filter(HarnessMacro.this.level(_, setup) == level)
          .map(method => invoke(target, method, environment, instance.arguments(method)))
      )

    def instantiate(state: Expr[Int]): Expr[AnyRef] =
      val cases = instances.toList.map { instance =>
        val constructor = defaultConstructor(instance.cls).get
        instance.id -> New(Inferred(instance.cls.typeRef)).select(constructor).appliedToNone.asExprOf[AnyRef]
      }
      choose(state, cases, '{ null: AnyRef })

    def assign(state: Expr[Int], target: Expr[AnyRef], parameter: Expr[String], text: Expr[String]): Expr[Unit] =
      val cases = instances.toList.map { instance =>
        instance.id -> sequence(parameterFields(instance.cls).map { field =>
          if !field.flags.is(Flags.Mutable) then fail("@Param requires a mutable var", field)
          // A macro cannot reach a private member of a class compiled earlier; JMH itself uses reflection here.
          if field.flags.is(Flags.Private) then
            fail(
              "@Param fields must not be private on Native and Scala.js; use a qualified private[...] instead",
              field
            )
          val tpe = fieldType(field)
          val parsed = parameterType(field) match
            case Some(parameter)                                        => parameter.parse(text).asTerm
            case None if tpe.classSymbol.exists(_.flags.is(Flags.Enum)) =>
              Select.unique(Ref(tpe.classSymbol.get.companionModule), "valueOf").appliedTo(text.asTerm)
            case None => fail(s"Unsupported @Param type ${tpe.show}", field)
          val assignment = Assign(cast(target, field.owner).select(field), parsed).asExprOf[Unit]
          '{ if $parameter == ${ Expr(field.name) } then $assignment }.asTerm
        })
      }
      choose(state, cases, '{ () })

    def fixture(
        setup: Boolean,
        state: Expr[Int],
        level: Expr[Int],
        target: Expr[AnyRef],
        environment: Expr[Environment]
    ): Expr[Unit] =
      val cases = instances.toList.map { instance =>
        val levels = (trial to invocation).toList.map { n =>
          n -> helpers(instance.id, setup, n, cast(target, instance.cls), environment)
        }
        instance.id -> choose(level, levels, '{ () })
      }
      choose(state, cases, '{ () })

    private def subgroup(environment: Expr[Environment]): Expr[Int] = '{ $environment.threadParams.getSubgroupIndex() }

    private def generatedEntries: List[(Symbol, Int)] =
      entries.zipWithIndex.filter((head, _) => eligible.contains(head))

    def auxiliary(benchmark: Expr[Int], environment: Expr[Environment]): Expr[Array[Double]] =
      val empty = '{ Array.emptyDoubleArray }
      val cases = generatedEntries.map { (head, index) =>
        val alternatives = groups(groupName(head)).zipWithIndex.map { (member, subgroup) =>
          val observed = counters(member).map { (id, counter) =>
            val accessor = invoke(state(environment, id), counter, environment).asExpr
            '{ if $environment.counterEnabled(${ Expr(counterKind(id)) }) then Values.counter($accessor) else 0.0 }
          }
          subgroup -> '{ Array[Double](${ Varargs(observed) }*) }
        }
        index -> choose(subgroup(environment), alternatives, empty)
      }
      choose(benchmark, cases, empty)

    /** Scala vars are accessor methods on the JVM, which JMH does not reset as it does Java public fields. Only
      * Java-defined counter fields are reset, keeping the shared-source behaviour.
      */
    def resetAuxiliary(benchmark: Expr[Int], environment: Expr[Environment]): Expr[Unit] =
      val cases = generatedEntries.map { (head, index) =>
        val alternatives = groups(groupName(head)).zipWithIndex.map { (member, subgroup) =>
          subgroup -> sequence(counters(member).flatMap { (id, counter) =>
            if counter.isDefDef || !instances(id).cls.flags.is(Flags.JavaDefined) then Nil
            else
              val zero = counterZeros(fieldType(counter).classSymbol.get)
              List(Assign(state(environment, id).select(counter), zero.asTerm))
          })
        }
        index -> choose(subgroup(environment), alternatives, '{ () })
      }
      choose(benchmark, cases, '{ () })

    private def compilerControl(benchmark: Symbol): Option[String] =
      annotation[CompilerControl](benchmark)
        .orElse(annotation[CompilerControl](benchmark.owner))
        .map(control => value(Some(control), "value", ""))

    /** The benchmark call, with its result consumed by the injected Blackhole. */
    private def call(benchmark: Symbol, environment: Expr[Environment]): Expr[Unit] =
      val invocation =
        invoke(state(environment, holders(benchmark)), benchmark, environment, benchmarkArguments(benchmark))
      val consumed =
        if returnsUnit(benchmark) then invocation.asExprOf[Unit]
        else Select.overloaded('{ $environment.blackhole }.asTerm, "consume", Nil, List(invocation)).asExprOf[Unit]
      compilerControl(benchmark) match
        case None | Some("INLINE") => consumed
        // Already a call boundary of its own.
        case Some("DONT_INLINE") if annotation[noinline](benchmark).nonEmpty => consumed
        // The benchmark body may be inlined into this boundary, but the boundary stays out of the measured loop.
        case Some("DONT_INLINE") => '{ @noinline def boundary(): Unit = $consumed; boundary() }
        case Some(other) => fail(s"CompilerControl.$other has no supported equivalent on this backend", benchmark)

    private def invocationFixtures(
        benchmark: Symbol,
        setup: Boolean,
        environment: Expr[Environment]
    ): (Boolean, Expr[Unit]) =
      val used = reachable(benchmark)
      val annotated = (if setup then used else used.reverse).filter { id =>
        fixtures(instances(id).cls, setup).exists(level(_, setup) == invocation)
      }
      val guarded = annotated.map { id =>
        val body = helpers(id, setup, invocation, state(environment, id), environment)
        '{ $environment.guarded(${ Expr(id) }, 2, ${ Expr(setup) })($body) }.asTerm
      }
      (annotated.nonEmpty, sequence(guarded))

    def measure(
        benchmark: Expr[Int],
        environment: Expr[Environment],
        control: Expr[IterationControl]
    ): Expr[RawResult] =
      val unknown = '{ throw new IllegalArgumentException }
      val loops = eligible.map { method =>
        val (hasSetup, setup) = invocationFixtures(method, true, environment)
        val (hasTeardown, teardown) = invocationFixtures(method, false, environment)
        val hasInvocationFixtures = Expr(hasSetup || hasTeardown)
        method -> '{
          Loops.run(
            $control,
            $environment.sampling,
            $hasInvocationFixtures,
            ${ call(method, environment) },
            $setup,
            $teardown,
            $environment.notifyControl
          )
        }
      }.toMap
      val cases = groups.toList.sortBy(_._1).flatMap { (_, members) =>
        val generated = members.filter(eligible.contains)
        generated.headOption.map { head =>
          entries.indexOf(head) ->
            choose(
              subgroup(environment),
              generated.zipWithIndex.map((member, index) => index -> loops(member)),
              unknown
            )
        }
      }
      choose(benchmark, cases, unknown)

    end measure

  end Generator

  def suite[T: Type]: Expr[Suite] =
    val cls = TypeRepr.of[T].typeSymbol
    validatePlatformTargets(cls)
    val model = new Model(cls)
    val generator = new Generator(model)
    val descriptors = Expr.ofSeq(model.entries.map(benchmark => Expr(metadata(model, benchmark))))
    '{
      new Suite:
        def metadata(): Array[String] = $descriptors.toArray
        def instantiate(state: Int): AnyRef = ${ generator.instantiate('state) }
        def assign(state: Int, target: AnyRef, parameter: String, value: String): Unit =
          ${ generator.assign('state, 'target, 'parameter, 'value) }
        def setup(state: Int, level: Int, target: AnyRef, environment: Environment): Unit =
          ${ generator.fixture(true, 'state, 'level, 'target, 'environment) }
        def teardown(state: Int, level: Int, target: AnyRef, environment: Environment): Unit =
          ${ generator.fixture(false, 'state, 'level, 'target, 'environment) }
        override def resetAuxiliary(benchmark: Int, environment: Environment): Unit =
          ${ generator.resetAuxiliary('benchmark, 'environment) }
        override def auxiliary(benchmark: Int, environment: Environment): Array[Double] =
          ${ generator.auxiliary('benchmark, 'environment) }
        @noinline @HarnessPlatform.boundary
        def measure(benchmark: Int, environment: Environment, control: IterationControl): RawResult =
          ${ generator.measure('benchmark, 'environment, 'control) }
    }

end HarnessMacro
