package scala.benchmark.runtime
import java.io.{ByteArrayOutputStream, PrintStream}
import org.junit.Test
import org.junit.Assert.*
class BenchmarkOutputTest {
  @Test def userScopeRestoresAmbientConsolesAndReportingOnFailure(): Unit = {
    val user = new ByteArrayOutputStream; val report = new ByteArrayOutputStream
    val userStream = new PrintStream(user); val reportStream = new PrintStream(report)
    Console.withOut(userStream) {
      BenchmarkOutput.capture {
        Console.withOut(reportStream) {
          assertTrue(
            scala.util
              .Try(BenchmarkOutput.user { println("user"); throw new IllegalArgumentException("fixture") })
              .isFailure
          )
          println("report")
        }
      }
    }
    assertEquals("user" + System.lineSeparator(), user.toString("UTF-8"))
    assertEquals("report" + System.lineSeparator(), report.toString("UTF-8"))
  }
  @Test def userScopeIsInheritedByWorkers(): Unit = {
    val user = new ByteArrayOutputStream; val report = new ByteArrayOutputStream
    Console.withOut(new PrintStream(user)) {
      BenchmarkOutput.capture {
        Console.withOut(new PrintStream(report)) {
          val worker = new Thread(() => BenchmarkOutput.user { println("worker") })
          worker.start(); worker.join(5000)
          assertFalse(worker.isAlive)
        }
      }
    }
    assertTrue(user.toString("UTF-8").contains("worker")); assertEquals(0, report.size())
  }
}
