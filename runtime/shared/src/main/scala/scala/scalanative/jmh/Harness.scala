package scala.scalanative.jmh

import java.util.concurrent.TimeUnit

import scala.benchmark.internal.BenchmarkSymbols
import scala.collection.mutable
import scala.quoted.*

import org.openjdk.jmh.infra.Blackhole

/** Derives the typed harness of one benchmark class.
  *
  * The benchmark call and its invocation fixtures are spliced into the inline [[Loops]] templates, so the measured loop
  * contains no callback. State wiring, fixture ordering and validation follow JMH 1.37.
  */
object Harness:
  inline def derive[T]: Suite = ${ deriveImpl[T] }

  private def deriveImpl[T: Type](using Quotes): Expr[Suite] = new HarnessMacro(quotes).suite[T]

private final class HarnessMacro[Q <: Quotes](q: Q) extends BenchmarkSymbols[Q](using q):
  import quotes.reflect.*

  private val blank = "blank_blank_blank_2014"
  private val trial = 0
  private val invocation = 2

  private val infrastructure = Set(
    "org.openjdk.jmh.infra.Blackhole",
    "org.openjdk.jmh.infra.ThreadParams",
    "org.openjdk.jmh.infra.BenchmarkParams",
    "org.openjdk.jmh.infra.IterationParams",
    "org.openjdk.jmh.infra.Control"
  )

  private val numeric = Set(
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

  private val parameterParsers = Map(
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

  private def className(tpe: TypeRepr): String = tpe.classSymbol.fold("")(_.fullName)

  private def returnsUnit(member: Symbol): Boolean = resultType(member) =:= TypeRepr.of[Unit]

  private def scope(cls: Symbol): String = value(inherited(cls, "State"), "value", "Thread")

  private def parameterFields(cls: Symbol): List[Symbol] =
    cls.typeRef.baseClasses.reverse.flatMap(_.declaredFields.filter(annotation(_, "Param").nonEmpty)).distinct

  /** Annotated declarations hidden by overrides are retained: JMH emits each declaration's helper call, with normal
    * virtual dispatch at the call site. JMH 1.37 orders helpers by qualified method name (RFMethodInfo.compareTo).
    */
  private def fixtures(cls: Symbol, setup: Boolean): List[Symbol] =
    cls.typeRef.baseClasses
      .flatMap(_.declaredMethods)
      .filter(annotation(_, if setup then "Setup" else "TearDown").nonEmpty)
      .distinct
      .sortBy(method => (method.owner.fullName, method.name))

  private def allFixtures(cls: Symbol): List[Symbol] = (fixtures(cls, true) ::: fixtures(cls, false)).distinct

  private def level(method: Symbol, setup: Boolean): Int =
    value(annotation(method, if setup then "Setup" else "TearDown"), "value", "Trial") match
      case "Trial"      => 0
      case "Iteration"  => 1
      case "Invocation" => 2
      case other        => fail(s"Unknown fixture level $other", method)

  private def stateArguments(method: Symbol): List[Symbol] =
    parameterTypes(method).flatMap { tpe =>
      tpe.classSymbol match
        case Some(cls) if infrastructure(cls.fullName)       => Nil
        case Some(cls) if inherited(cls, "State").nonEmpty   => List(cls)
        case _ => fail("Arguments must be @State classes or JMH infrastructure (Blackhole, *Params, Control)", method)
    }

  // ---------------------------------------------------------------------------------------------------------------
  // Model
  // ---------------------------------------------------------------------------------------------------------------

  /** JMH allocates Thread state per injection site. Benchmark/Group states collapse by class, with runtime workers
    * applying their sharing scope.
    */
  private final case class StateInstance(cls: Symbol, id: Int, arguments: Map[Symbol, List[Int]]):
    def dependencies: List[Int] = arguments.values.flatten.toList.distinct.sorted

  private final class Model(val benchmarkClass: Symbol):
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
      if annotation(method, "Group").nonEmpty && annotation(method, "Threads").nonEmpty then
        fail("@Threads is ambiguous on a grouped method; use @GroupThreads", method)
      if annotation(method, "GroupThreads").nonEmpty && annotation(method, "Group").isEmpty then
        fail("@GroupThreads requires @Group", method)
      stateArguments(method).foreach(visit)
    }

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
        if inherited(cls, "AuxCounters").nonEmpty then auxiliaryMembers(cls) = auxiliaryCounters(cls)
        active += cls
        val helpers = allFixtures(cls)
        helpers.foreach { method =>
          if isRestricted(method) || !returnsUnit(method) then fail("Fixture methods must be public and return Unit", method)
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
        if !numeric(className(resultType(member))) then fail("Public auxiliary counters must have numeric types", member)
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

    def counterKind(id: Int): String = value(inherited(instances(id).cls, "AuxCounters"), "value", "OPERATIONS")
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

  private def isBlank(annotation: String, text: String): Boolean =
    text == blank || (Set("Warmup", "Measurement", "Fork")(annotation) && text == "-1")

  private def metadata(model: Model, benchmark: Symbol): String =
    import model.*
    val properties = mutable.Map.empty[String, String]
    def put(key: String, text: String): Unit = properties(key) = text
    val group = groupName(benchmark)
    val members = groups(group)
    val cache = mutable.Map.empty[String, List[Term]]

    /** Members of one group must agree on every configuration annotation they declare, as in JMH. */
    def groupAnnotations(name: String): List[Term] = cache.getOrElseUpdate(
      name, {
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
          benchmarkClass.typeRef.baseClasses.filterNot(_.flags.is(Flags.Trait)).flatMap(annotation(_, name))
        var result = List.empty[Term]
        members.foreach { method =>
          val annotations = annotation(method, name).toList ::: inheritedAnnotations
          if result.nonEmpty && annotations.nonEmpty && result.map(declared) != annotations.map(declared) then
            fail(s"Colliding $name annotations in group $group", method)
          result = annotations
        }
        result
      }
    )
    def groupValue(name: String, argument: String, default: String): String =
      groupAnnotations(name).iterator
        .map(annotation => value(Some(annotation), argument, default))
        .find(!isBlank(name, _))
        .getOrElse(default)
    def number(annotation: String, argument: String, key: String, allowZero: Boolean = false): Unit =
      val declared = groupValue(annotation, argument, "-1").toInt
      if declared != -1 then
        if declared < (if allowZero then 0 else 1) then fail(s"Invalid $annotation.$argument: $declared", benchmark)
        put(key, declared.toString)
    def duration(name: String, prefix: String): Unit =
      groupAnnotations(name).find(annotation => value(Some(annotation), "time", "-1") != "-1").foreach { annotation =>
        val time = value(Some(annotation), "time", "-1").toLong
        if time < 0 then fail(s"$name.time must be nonnegative", benchmark)
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
        annotation(member, "BenchmarkMode")
          .orElse(inherited(benchmarkClass, "BenchmarkMode"))
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
    number("Warmup", "iterations", "warmupIterations", allowZero = true)
    number("Measurement", "iterations", "measurementIterations")
    number("Warmup", "batchSize", "warmupBatchSize")
    number("Measurement", "batchSize", "measurementBatchSize")
    duration("Warmup", "warmup")
    duration("Measurement", "measurement")
    duration("Timeout", "timeout")
    number("Fork", "value", "forks", allowZero = true)
    number("Fork", "warmups", "warmupForks", allowZero = true)
    put("threads", groupValue("Threads", "value", "1"))
    number("OperationsPerInvocation", "value", "operationsPerInvocation")
    put("unit", groupValue("OutputTimeUnit", "value", "SECONDS"))

    val grouped = members.exists(annotation(_, "Group").nonEmpty)
    val generated = eligible.contains(benchmark)
    val used = if generated then members.flatMap(reachable).distinct else Nil
    if generated && !grouped && used.exists(id => scope(instances(id).cls) == "Group") then
      fail("Scope.Group requires a grouped benchmark", benchmark)
    if grouped then
      put("members", members.size.toString)
      members.zipWithIndex.foreach { (member, index) =>
        val threads = value(annotation(member, "GroupThreads"), "value", "1").toInt
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
        if inherited(cls, "State").isEmpty then fail("@Param requires @State", field)
        val values = parameterValues(field)
        values.foreach { text =>
          if !validParameter(field, text) then fail(s"Invalid @Param value '$text' for ${field.name}", field)
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

    val ignored = groupAnnotations("Fork").headOption.toList.flatMap(arguments).filter(_._1.startsWith("jvm")).flatMap {
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
    val declared = arguments(annotation(field, "Param").get).get("value").map(strings).getOrElse(Nil)
    if declared.nonEmpty && declared != List(blank) then declared
    else
      val enumClass = fieldType(field).classSymbol.filter(_.flags.is(Flags.Enum)).getOrElse {
        fail("@Param requires explicit values unless its type is an enum", field)
      }
      enumClass.companionModule.moduleClass.declaredFields
        .filter(member => member.flags.is(Flags.Enum) && member.flags.is(Flags.StableRealizable))
        .map(_.name)

  private def validParameter(field: Symbol, text: String): Boolean =
    try
      className(fieldType(field)) match
        case "scala.Boolean" | "java.lang.Boolean" => text == "true" || text == "false"
        case "scala.Char" | "java.lang.Character"  => text.length == 1
        case "scala.Byte" | "java.lang.Byte"       => java.lang.Byte.parseByte(text); true
        case "scala.Short" | "java.lang.Short"     => java.lang.Short.parseShort(text); true
        case "scala.Int" | "java.lang.Integer"     => java.lang.Integer.parseInt(text); true
        case "scala.Long" | "java.lang.Long"       => java.lang.Long.parseLong(text); true
        case "scala.Float" | "java.lang.Float"     => java.lang.Float.parseFloat(text); true
        case "scala.Double" | "java.lang.Double"   => java.lang.Double.parseDouble(text); true
        case _                                     => true
    catch case _: NumberFormatException => false

  // ---------------------------------------------------------------------------------------------------------------
  // Code generation
  // ---------------------------------------------------------------------------------------------------------------

  private def choose[A: Type](selector: Expr[Int], cases: List[(Int, Expr[A])], default: Expr[A]): Expr[A] =
    cases.foldRight(default) { case ((index, body), next) => '{ if $selector == ${ Expr(index) } then $body else $next } }

  private def sequence(statements: List[Term]): Expr[Unit] = Block(statements, '{ () }.asTerm).asExprOf[Unit]

  private def cast(target: Expr[AnyRef], cls: Symbol): Term =
    cls.typeRef.asType match
      case '[t] => '{ $target.asInstanceOf[t] }.asTerm

  private def values(method: String, argument: Term): Term =
    Select.unique(Ref(Symbol.requiredModule("scala.scalanative.jmh.Values")), method).appliedTo(argument)

  private final class Generator(model: Model):
    import model.*

    def state(environment: Expr[Environment], id: Int): Term =
      cast('{ $environment.state(${ Expr(id) }) }, instances(id).cls)

    private def injected(environment: Expr[Environment], tpe: TypeRepr, states: Iterator[Int]): Term =
      className(tpe) match
        case "org.openjdk.jmh.infra.Blackhole"       => '{ $environment.blackhole }.asTerm
        case "org.openjdk.jmh.infra.ThreadParams"    => '{ $environment.threadParams }.asTerm
        case "org.openjdk.jmh.infra.BenchmarkParams" => '{ $environment.benchmarkParams }.asTerm
        case "org.openjdk.jmh.infra.IterationParams" => '{ $environment.iterationParams }.asTerm
        case "org.openjdk.jmh.infra.Control"         => '{ $environment.notifyControl }.asTerm
        case _                                       => state(environment, states.next())

    /** Applies every parameter list; type parameters are instantiated to their upper bounds, as after erasure. */
    def invoke(target: Term, method: Symbol, environment: Expr[Environment], states: List[Int] = Nil): Term =
      val remaining = states.iterator
      def applyLists(function: Term): Term = function.tpe.widen match
        case poly: PolyType => applyLists(function.appliedToTypes(poly.paramBounds.map(_.hi)))
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
          if isRestricted(field) then fail("@Param fields must be non-private vars on Native and Scala.js", field)
          val tpe = fieldType(field)
          val parsed = parameterParsers.get(className(tpe)) match
            case Some(parser) => values(parser, text.asTerm)
            case None if tpe.classSymbol.exists(_.flags.is(Flags.Enum)) =>
              Select.unique(Ref(tpe.classSymbol.get.companionModule), "valueOf").appliedTo(text.asTerm)
            case None => fail(s"Unsupported @Param type ${className(tpe)}", field)
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

    private def generatedEntries: List[(Symbol, Int)] = entries.zipWithIndex.filter((head, _) => eligible.contains(head))

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
              val tpe = fieldType(counter)
              val name = className(tpe)
              val zero =
                if name.startsWith("java.lang.") then
                  tpe.asType match
                    case '[t] => '{ Values.zero(${ Expr(name) }).asInstanceOf[t] }.asTerm
                else values(name.stripPrefix("scala.").toLowerCase, Literal(StringConstant("0")))
              List(Assign(state(environment, id).select(counter), zero))
          })
        }
        index -> choose(subgroup(environment), alternatives, '{ () })
      }
      choose(benchmark, cases, '{ () })

    private def compilerControl(benchmark: Symbol): Option[String] =
      annotation(benchmark, "CompilerControl")
        .orElse(annotation(benchmark.owner, "CompilerControl"))
        .map(control => value(Some(control), "value", ""))

    /** The benchmark call, with its result consumed by the injected Blackhole. */
    private def call(benchmark: Symbol, environment: Expr[Environment]): Expr[Unit] =
      val invocation = invoke(state(environment, holders(benchmark)), benchmark, environment, benchmarkArguments(benchmark))
      val consumed =
        if returnsUnit(benchmark) then invocation.asExprOf[Unit]
        else Select.overloaded('{ $environment.blackhole }.asTerm, "consume", Nil, List(invocation)).asExprOf[Unit]
      compilerControl(benchmark) match
        case None | Some("INLINE") => consumed
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
            choose(subgroup(environment), generated.zipWithIndex.map((member, index) => index -> loops(member)), unknown)
        }
      }
      choose(benchmark, cases, unknown)
  end Generator

  def suite[T: Type]: Expr[Suite] =
    val cls = TypeRepr.of[T].typeSymbol
    validatePlatformTargets(cls)
    val model = new Model(cls)
    val generator = new Generator(model)
    val descriptors = Expr.ofSeq(model.entries.map(benchmark => Expr(metadata(model, benchmark))))
    '{
      new Suite {
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
    }
