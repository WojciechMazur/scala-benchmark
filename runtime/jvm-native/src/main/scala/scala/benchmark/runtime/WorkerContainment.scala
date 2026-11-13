package scala.benchmark.runtime

private[runtime] object WorkerContainment:
  private var processes = Vector.empty[Process]
  private var survivors = Vector.empty[Thread]

  def check(): Unit = synchronized {
    survivors = survivors.filter(_.isAlive)
    processes = processes.filter(_.isAlive)
    if processes.nonEmpty then throw new UnfinishedWorkersException(Vector.fill(processes.size)("fork process"), null)
    if survivors.nonEmpty then throw new UnfinishedWorkersException(survivors.map(_.getName), null)
  }

  def quarantine(process: Process, cause: Throwable): UnfinishedWorkersException = synchronized {
    processes = (processes :+ process).distinct.filter(_.isAlive)
    new UnfinishedWorkersException(Vector.fill(processes.size)("fork process"), cause)
  }

  def quarantine(workers: Vector[Thread], cause: Throwable): UnfinishedWorkersException = synchronized {
    survivors = (survivors ++ workers).distinct.filter(_.isAlive)
    new UnfinishedWorkersException(survivors.map(_.getName), cause)
  }
