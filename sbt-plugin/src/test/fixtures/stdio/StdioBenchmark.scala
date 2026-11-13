package scala.benchmark.runtime.stdio

import scala.benchmark.*
import java.io.{ByteArrayOutputStream, PrintStream}

object UserMessages {
  def emit(stage: String): Unit = {
    Console.print("C-OUT:" + stage + ";")
    System.out.print("S-OUT:" + stage + ";")
    Console.err.print("C-ERR:" + stage + ";")
    System.err.print("S-ERR:" + stage + ";")
  }
  val binary: Array[Byte] = Array.tabulate[Byte](256)(_.toByte)
}
@State(Scope.Thread)
class UserState {
  UserMessages.emit("CTOR")
  @Param(Array("ok")) var action: String = "ok"
  @Setup(Level.Trial) def trial(): Unit = UserMessages.emit("TRIAL")
  @Setup(Level.Iteration) def iteration(): Unit = UserMessages.emit("ITER")
  @Setup(Level.Invocation) def invocation(): Unit = UserMessages.emit("INVOC")
  @TearDown(Level.Invocation) def invocationEnd(): Unit = UserMessages.emit("INVOC-END")
  @TearDown(Level.Iteration) def iterationEnd(): Unit = UserMessages.emit("ITER-END")
  @TearDown(Level.Trial) def trialEnd(): Unit = UserMessages.emit("TRIAL-END")
}
@BenchmarkMode(Array(Mode.SingleShotTime)) @Warmup(iterations = 0) @Measurement(iterations = 1) @Fork(0)
class UserTarget {
  @Benchmark def execute(state: UserState): Long = {
    UserMessages.emit("BODY")
    val unicode = "λ🦊".getBytes("UTF-8")
    unicode.foreach(value => System.out.write(value.toInt & 255))
    System.out.write(UserMessages.binary)
    System.err.write(UserMessages.binary.reverse)
    System.out.flush(); System.err.flush()
    if (state.action == "fail") throw new IllegalStateException("STDIO-FAIL")
    42L
  }
}

class Capture {
  val bytes = new ByteArrayOutputStream
  var closed = false
  val stream = new PrintStream(bytes, false, "UTF-8") {
    override def close(): Unit = { closed = true; super.close() }
  }
  def hex: String = bytes.toByteArray.iterator.map(value => f"${value & 255}%02x").mkString
}
@BenchmarkMode(Array(Mode.SingleShotTime)) @Warmup(iterations = 0) @Measurement(iterations = 1) @Fork(0)
class StdioBenchmark {
  @Benchmark def execute(): Int = {
    var count = 0
    for (
      forks <- Vector(0, 1); verbosity <- Vector(VerboseMode.NORMAL, VerboseMode.SILENT);
      custom <- Vector(false, true); action <- Vector("ok", "fail")
    ) {
      val out = new Capture; val err = new Capture
      val consoleOut = new Capture; val consoleErr = new Capture; val formatOut = new Capture
      val beforeOut = System.out; val beforeErr = System.err
      val id = s"$forks-${verbosity.name().toLowerCase}-$custom-$action"
      val options = new OptionsBuilder()
        .include("scala.benchmark.runtime.stdio.UserTarget.execute")
        .mode(Mode.SingleShotTime)
        .warmupIterations(0)
        .measurementIterations(1)
        .forks(forks)
        .param("action", action)
        .verbosity(verbosity)
        .shouldFailOnError(false)
        .output("stdio-framework-" + id + ".log")
        .build()
      try {
        System.setOut(out.stream); System.setErr(err.stream)
        Console.withOut(consoleOut.stream) {
          Console.withErr(consoleErr.stream) {
            val runner =
              if (custom) new Runner(options, OutputFormatFactory.createFormatInstance(formatOut.stream, verbosity))
              else new Runner(options)
            val results = runner.run()
            assert(results.size() == (if (action == "ok") 1 else 0))
            if (action == "ok") assert(results.iterator().next().getPrimaryResult().getSampleCount() == 1)
          }
        }
        assert(!out.closed && !err.closed && !consoleOut.closed && !consoleErr.closed && !formatOut.closed)
        val formatText = formatOut.bytes.toString("UTF-8")
        assert(
          !formatText.contains("C-OUT:") && !formatText.contains("S-OUT:") && !formatText.contains(
            "C-ERR:"
          ) && !formatText.contains("S-ERR:"),
          formatText
        )
        Transcripts.write(id + "-system-out", out.hex)
        Transcripts.write(id + "-system-err", err.hex)
        Transcripts.write(id + "-console-out", consoleOut.hex)
        Transcripts.write(id + "-console-err", consoleErr.hex)
        out.stream.print("owner-open"); err.stream.print("owner-open")
      } finally { System.setOut(beforeOut); System.setErr(beforeErr) }
      count += 1
    }
    for (forks <- Vector(0, 1)) {
      val out = new Capture; val err = new Capture
      val beforeOut = System.out; val beforeErr = System.err
      val options = new OptionsBuilder()
        .include("scala.benchmark.runtime.stdio.UserTarget.execute")
        .warmupIterations(0)
        .measurementIterations(1)
        .forks(forks)
        .verbosity(VerboseMode.NORMAL)
        .shouldFailOnError(true)
        .build()
      try {
        System.setOut(out.stream); System.setErr(err.stream)
        Console.withOut(out.stream) {
          Console.withErr(err.stream) { assert(new Runner(options).run().size() == 1) }
        }
        val text = out.bytes.toString("UTF-8")
        assert(text.contains("# ") && text.contains("C-OUT:BODY;") && text.contains("S-OUT:BODY;"), text)
        assert(!out.closed && !err.closed)
      } finally { System.setOut(beforeOut); System.setErr(beforeErr) }
    }
    println("STDIO_CHANNELS_OK:" + count)
    count
  }
}

@BenchmarkMode(Array(Mode.SingleShotTime)) @Warmup(iterations = 0) @Measurement(iterations = 1) @Fork(0)
class RawTarget {
  @Benchmark def execute(): Int = {
    System.out.write("USER-OUT-BEGIN|".getBytes("UTF-8"))
    System.out.write(UserMessages.binary)
    System.out.write("|USER-OUT-END".getBytes("UTF-8"))
    System.err.write("USER-ERR-BEGIN|".getBytes("UTF-8"))
    System.err.write(UserMessages.binary.reverse)
    System.err.write("|USER-ERR-END".getBytes("UTF-8"))
    System.out.flush(); System.err.flush()
    1
  }
}
