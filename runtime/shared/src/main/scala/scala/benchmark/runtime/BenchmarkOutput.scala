package scala.benchmark.runtime

import java.io.PrintStream

/** Bind user consoles once outside timed loops; reporting retains its own console scope. */
private[runtime] object BenchmarkOutput:
  final case class Streams(out: PrintStream, err: PrintStream)
  private val current = new scala.util.DynamicVariable[Option[Streams]](None)
  def console: Streams = Streams(Console.out, Console.err)

  def withConsole[A](streams: Streams)(body: => A): A =
    Console.withOut(streams.out)(Console.withErr(streams.err)(body))

  def capture[A](body: => A): A = current.withValue(Some(console))(body)
  def user[A](body: => A): A = current.value.fold(body)(streams => withConsole(streams)(body))

  /** A fork owns these streams; OS startup/foreign-code output remains on the original descriptors. */
  def worker[A](out: PrintStream, err: PrintStream, reporting: Streams)(body: => A): A =
    val priorOut = System.out
    val priorErr = System.err
    System.setOut(out)
    System.setErr(err)
    try current.withValue(Some(Streams(out, err)))(withConsole(reporting)(body))
    finally
      System.setOut(priorOut)
      System.setErr(priorErr)
      try out.close()
      finally err.close()
