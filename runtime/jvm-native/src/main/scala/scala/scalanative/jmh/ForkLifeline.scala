package scala.scalanative.jmh

/** The coordinator owns the write end of worker stdin. EOF also arrives after an uncatchable coordinator death, without
  * involving benchmark threads.
  */
private[jmh] object ForkLifeline {
  def start(input: java.io.InputStream, lost: () => Unit): Thread = {
    val guard = new Thread(
      new Runnable {
        def run(): Unit = {
          try { while (input.read() != -1) () }
          finally lost()
        }
      },
      "benchmark-fork-lifeline"
    )
    guard.setDaemon(true)
    guard.start()
    guard
  }
}
