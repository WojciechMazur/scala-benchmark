package scala.benchmark.runtime

import java.net.URLClassLoader
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import javax.tools.ToolProvider
import org.junit.Assert.*
import org.junit.Test
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.generators.core.{BenchmarkGenerator, FileSystemDestination}
import org.openjdk.jmh.generators.reflection.RFGeneratorSource
import org.openjdk.jmh.infra.{Control, ThreadParams}
import org.openjdk.jmh.runner.InfraControl
import scala.jdk.CollectionConverters.*

/** Both languages are emitted from the same state graph; JMH supplies the independent lifecycle implementation. */
class StateGraphParityTest {
  private case class Node(name: String, scope: String, dependencies: Vector[String])
  private val nodes = Vector(
    Node("Root", "Benchmark", Vector.empty),
    Node("Left", "Benchmark", Vector("Root")),
    Node("Right", "Benchmark", Vector("Root")),
    Node("SharedGroup", "Group", Vector("Left", "Right")),
    Node("Local", "Thread", Vector("Root", "SharedGroup"))
  )
  private val journalSource = """package execution.state;
    |import java.util.*;
    |public class Journal {
    | static final Map<Object,List<String>> events = new IdentityHashMap<Object,List<String>>();
    | static final Map<Object,String> names = new IdentityHashMap<Object,String>();
    | static final Map<String,Object> uses = new TreeMap<String,Object>();
    | public static String fail = "";
    | public static int failures;
    | public static synchronized void event(String name, Object obj, String event) {
    |   names.put(obj,name);
    |   if (!events.containsKey(obj)) events.put(obj,new ArrayList<String>());
    |   events.get(obj).add(event);
    |   if ((name+"."+event).equals(fail)) { failures++; throw new IllegalStateException(fail); }
    | }
    | public static synchronized void use(int worker, String role, Object obj) {
    |   String key=worker+":"+role;
    |   if (uses.containsKey(key) && uses.get(key)!=obj) throw new AssertionError("State changed: "+key);
    |   uses.put(key,obj);
    | }
    | public static synchronized String snapshot() {
    |   List<String> traces=new ArrayList<String>();
    |   for (Object obj: events.keySet()) traces.add(names.get(obj)+":"+events.get(obj));
    |   Collections.sort(traces);
    |   List<String> identities=new ArrayList<String>();
    |   for (String a:uses.keySet()) for(String b:uses.keySet())
    |     if(a.compareTo(b)<=0 && uses.get(a)==uses.get(b)) identities.add(a+"="+b);
    |   return traces.toString()+"\n"+identities.toString();
    | }
    | public static synchronized void reset(String stage) { events.clear(); names.clear(); uses.clear(); failures=0; fail=stage; }
    |}
    |""".stripMargin

  private def stateSource(java: Boolean, overrides: Int, sharedInvocation: Boolean = false): String = {
    val header =
      if (java)
        "package execution.state; import org.openjdk.jmh.annotations.*; import org.openjdk.jmh.infra.ThreadParams; public class Graph {"
      else "package execution.state\nimport scala.benchmark.*\n"
    def event(name: String, stage: String): String = s"Journal.event(\"$name\", this, \"$stage\");"
    val base =
      if (java)
        "@State(Scope.Benchmark) public static class Base { @Setup(Level.Trial) public void baseStart() { " + event(
          "Root",
          "baseStart"
        ) + " } @TearDown(Level.Trial) public void baseEnd() { " + event("Root", "baseEnd") + " } }"
      else
        "@State(Scope.Benchmark) class Base { @Setup(Level.Trial) def baseStart(): Unit = { " + event(
          "Root",
          "baseStart"
        ) + " }; @TearDown(Level.Trial) def baseEnd(): Unit = { " + event("Root", "baseEnd") + " } }"
    val states = nodes
      .map { node =>
        val parent = if (node.name == "Root") " extends Base" else ""
        val start =
          if (java) s"@State(Scope.${node.scope}) public static class ${node.name}$parent {"
          else s"@State(Scope.${node.scope}) class ${node.name}$parent {"
        val fields = node.dependencies
          .map { dep =>
            if (java) s"public $dep saved$dep;" else s"var saved$dep: $dep = null;"
          }
          .mkString(" ")
        val levels = Vector("Trial", "Iteration") ++ (if (node.name == "Local" || sharedInvocation) Vector("Invocation")
                                                      else Vector.empty)
        val fixtures = for (level <- levels; setup <- Vector(true, false)) yield {
          val method = (if (setup) "start" else "end") + level
          val args = node.dependencies.map(dep => if (java) s"$dep arg$dep" else s"arg$dep: $dep").mkString(", ")
          val body = node.dependencies
            .map { dep =>
              if (setup && level == "Trial") s"saved$dep = arg$dep;"
              else if (java) s"if (saved$dep != arg$dep) throw new AssertionError(\"Dependency changed\");"
              else s"assert(saved$dep eq arg$dep);"
            }
            .mkString(" ") + event(node.name, method)
          val annotation = s"@${if (setup) "Setup" else "TearDown"}(Level.$level)"
          if (java) s"$annotation public void $method($args) { $body }"
          else s"$annotation def $method($args): Unit = { $body }"
        }
        val overridden =
          if (node.name != "Root" || overrides == 0) ""
          else
            Vector("baseStart", "baseEnd")
              .map { name =>
                val annotation =
                  if (overrides == 2) ""
                  else if (name == "baseStart") "@Setup(Level.Trial)"
                  else "@TearDown(Level.Trial)"
                if (java) s"$annotation @Override public void $name() { ${event("Root", name + "Override")} }"
                else s"$annotation override def $name(): Unit = { ${event("Root", name + "Override")} }"
              }
              .mkString("\n")
        val constructor =
          if (java) s"public ${node.name}() { ${event(node.name, "construct")} }" else event(node.name, "construct")
        start + constructor + fields + overridden + "\n" + fixtures.mkString("\n") + "}"
      }
      .mkString("\n")
    val body =
      "Journal.use(p.getThreadIndex(), \"local\", local); Journal.use(p.getThreadIndex(), \"group\", group); Journal.use(p.getThreadIndex(), \"root\", root);"
    val checks =
      if (java)
        "if (local.savedRoot != root || local.savedSharedGroup != group || group.savedLeft.savedRoot != root || group.savedRight.savedRoot != root) throw new AssertionError(\"Diamond identity\");"
      else
        "assert((local.savedRoot eq root) && (local.savedSharedGroup eq group) && (group.savedLeft.savedRoot eq root) && (group.savedRight.savedRoot eq root));"
    val methods = Vector("a", "b")
      .map { name =>
        val ann = "@Benchmark @Group(\"identity\") @GroupThreads(1)"
        if (java)
          s"$ann public int $name(Local local, SharedGroup group, Root root, ThreadParams p) { $checks $body return 1; }"
        else s"$ann def $name(local: Local, group: SharedGroup, root: Root, p: ThreadParams): Int = { $checks $body 1 }"
      }
      .mkString("\n")
    val benchmark = (if (java) "public static class BenchmarkGraph {" else "class BenchmarkGraph {") + methods + "}"
    header + base + "\n" + states + "\n" + benchmark + (if (java) "}" else "")
  }

  private final class Fixture(overrides: Int = 0, baseName: String = "Base", sharedInvocation: Boolean = false) {
    val directory = Files.createDirectories(
      Paths
        .get("target/execution-parity/state-graphs")
        .toAbsolutePath
        .resolve(java.lang.Long.toUnsignedString(System.nanoTime()))
    )
    val classes = Files.createDirectories(directory.resolve("classes"))
    val generated = Files.createDirectories(directory.resolve("generated"))
    val resources = Files.createDirectories(directory.resolve("resources"))
    val classpath = HarnessCompiler.classpath + java.io.File.pathSeparator + classes
    def write(name: String, text: String): Path = {
      val path = directory.resolve(name); Files.write(path, text.getBytes(UTF_8)); path
    }
    def javac(files: Vector[Path]): Unit = {
      val diagnostics = new java.io.ByteArrayOutputStream
      val status = ToolProvider.getSystemJavaCompiler.run(
        null,
        diagnostics,
        diagnostics,
        (Vector("--release", "8", "-classpath", classpath, "-d", classes.toString) ++ files.map(_.toString))*
      )
      assertEquals(diagnostics.toString("UTF-8"), 0, status)
    }
    javac(
      Vector(
        write("Journal.java", journalSource),
        write("Graph.java", stateSource(true, overrides, sharedInvocation).replace("Base", baseName))
      )
    )
    val loader = new URLClassLoader(Array(classes.toUri.toURL), getClass.getClassLoader)
    val input = new RFGeneratorSource
    input.processClasses(loader.loadClass("execution.state.Graph$BenchmarkGraph"))
    val destination = new FileSystemDestination(resources.toFile, generated.toFile)
    val generator = new BenchmarkGenerator
    generator.generate(input, destination); generator.complete(input, destination)
    assertFalse(destination.getErrors.asScala.mkString("\n"), destination.hasErrors)
    val stream = Files.walk(generated)
    val paths = try stream.iterator.asScala.filter(_.toString.endsWith(".java")).toVector
    finally stream.close()
    javac(paths)
    val scalaInput = write("Graph.scala", stateSource(false, overrides, sharedInvocation).replace("Base", baseName))
    assertTrue(
      "Portable graph did not compile",
      HarnessCompiler.recompile(classes, scalaInput) &&
        HarnessCompiler.derive(classes, Seq("execution.state.BenchmarkGraph"))
    )
    private def fingerprint(path: Path): String = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(Files.readAllBytes(path))
      .map(value => f"${value & 0xff}%02x")
      .mkString
    private val artifacts = Vector(classOf[Mode], classOf[RFGeneratorSource]).map(c =>
      Paths.get(c.getProtectionDomain.getCodeSource.getLocation.toURI)
    )
    require(artifacts.take(2).forall(_.getFileName.toString.endsWith("-1.37.jar")), "State oracle requires JMH 1.37")
    write(
      "provenance.txt",
      (Vector(
        "jdk=" + System.getProperty("java.runtime.version"),
        "os=" + System.getProperty("os.name") + "/" + System.getProperty("os.arch"),
        "compiler=" + dotty.tools.dotc.config.Properties.versionNumberString,
        s"overrides=$overrides; base=$baseName"
      ) ++ (artifacts ++ paths ++ Vector(
        directory.resolve("Journal.java"),
        directory.resolve("Graph.java"),
        scalaInput
      ))
        .map(path => path.toString + ".sha256=" + fingerprint(path))).mkString("\n") + "\n"
    )
    val suite = HarnessCompiler.suite(loader, "execution.state.BenchmarkGraph")
    val descriptor = Descriptor.parse(0, suite.metadata()(0))
    val journal = loader.loadClass("execution.state.Journal")
    def reset(stage: String): Unit = { journal.getMethod("reset", classOf[String]).invoke(null, stage); () }
    def failures: Int = journal.getField("failures").getInt(null)
    def snapshot: String = journal.getMethod("snapshot").invoke(null).asInstanceOf[String]
    def upstream(config: Configuration): Unit = {
      val harness = loader.loadClass("execution.state.jmh_generated.Graph_BenchmarkGraph_identity_jmhTest")
      val sessions = Vector.fill(config.threads)(harness.getConstructor().newInstance())
      val params = Infrastructure.benchmark(descriptor, Mode.SingleShotTime, config, Map.empty)
      for (iteration <- 0 until config.warmupIterations + config.measurementIterations) {
        val ip = if (iteration < config.warmupIterations) params.getWarmup() else params.getMeasurement()
        val control = new InfraControl(
          params,
          ip,
          new CountDownLatch(config.threads),
          new CountDownLatch(config.threads),
          iteration == 0,
          iteration == config.warmupIterations + config.measurementIterations - 1,
          false,
          new Control
        )
        val failures = new java.util.concurrent.ConcurrentLinkedQueue[Throwable]
        val workers = sessions.zipWithIndex.map { case (session, index) =>
          val thread = new Thread(
            new Runnable {
              def run(): Unit = try {
                harness
                  .getMethod("identity_SingleShotTime", classOf[InfraControl], classOf[ThreadParams])
                  .invoke(session, control, descriptor.worker(index, config.threads))
              } catch {
                case error: Throwable =>
                  failures.add(error); control.isFailing = true; control.preSetupForce(); control.preTearDownForce()
              }
            },
            s"state-oracle-$index"
          )
          thread.setDaemon(true); thread
        }
        workers.foreach(_.start()); workers.foreach(_.join(5000))
        assertFalse("JMH graph left a worker running", workers.exists(_.isAlive))
        if (!failures.isEmpty) {
          val causes = failures.asScala.toVector.map(t => Option(t.getCause).getOrElse(t))
          throw causes.find(_.isInstanceOf[IllegalStateException]).getOrElse(causes.head)
        }
      }
    }
  }

  @Test(timeout = 60000L) def dependencyDiamondAndInheritedFixturesMatchJmh(): Unit = compareGraph(0)
  @Test(timeout = 60000L) def overriddenFixturesMatchJmh(): Unit = compareGraph(1)
  @Test(timeout = 60000L) def unannotatedOverridesRetainInheritedFixtureCalls(): Unit = compareGraph(2)
  @Test(timeout = 60000L) def helperOrderUsesQualifiedNameRatherThanHierarchy(): Unit = compareGraph(0, "ZBase")
  private def compareGraph(overrides: Int, baseName: String = "Base"): Unit = {
    val fixture = new Fixture(overrides, baseName)
    try {
      for (threads <- Vector(2, 4); warmup <- Vector(0, 1); batch <- Vector(1, 3)) {
        val config = Configuration(
          threads = threads,
          warmupIterations = warmup,
          measurementIterations = 2,
          warmupBatchSize = batch,
          measurementBatchSize = batch,
          timeoutNanos = 2000000000L
        )
        fixture.reset(""); fixture.upstream(config)
        val expected = fixture.snapshot
        fixture.reset("")
        val results = Execution.trial(fixture.suite, fixture.descriptor, Mode.SingleShotTime, config, Map.empty)
        val actual = fixture.snapshot
        fixture.write(s"threads-$threads-warmup-$warmup-batch-$batch.txt", s"upstream=$expected\nportable=$actual\n")
        assertEquals(expected, actual)
        assertEquals(2, results.size)
        assertTrue(results.forall(_.workers.size == threads))
      }
    } finally fixture.loader.close()
  }
  @Test(timeout = 120000L) def sharedFixtureFailureIsNotRetriedByPeers(): Unit = {
    val config = Configuration(threads = 2, warmupIterations = 0, measurementIterations = 1, timeoutNanos = 1000000000L)
    for (
      node <- Vector("Root", "SharedGroup");
      stage <- Vector(
        "construct",
        "startTrial",
        "startIteration",
        "endIteration",
        "endTrial",
        "startInvocation",
        "endInvocation"
      )
    ) {
      // Failed JMH trials retain static state; isolate each failure in its own loader.
      val fixture = new Fixture(sharedInvocation = stage.endsWith("Invocation"))
      try {
        val failure = node + "." + stage
        fixture.reset(failure)
        val expected = scala.util.Try(fixture.upstream(config)).failed.get
        assertEquals(failure, expected.getMessage)
        val referenceFailures = fixture.failures
        assertEquals("Reference shared fixture invoked more than once", 1, referenceFailures)
        fixture.reset(failure)
        val actual = scala.util
          .Try(Execution.trial(fixture.suite, fixture.descriptor, Mode.SingleShotTime, config, Map.empty))
          .failed
          .get
        fixture.write(
          "failure-" + failure + ".txt",
          s"upstream=$referenceFailures; portable=${fixture.failures}; cause=${actual.getMessage}\n${fixture.snapshot}\n"
        )
        assertEquals(failure, actual.getMessage)
        assertEquals(failure + ": failed shared fixture was retried", referenceFailures, fixture.failures)
      } finally fixture.loader.close()
    }
  }
}
