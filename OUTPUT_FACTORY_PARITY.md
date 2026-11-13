# Standard OutputFormatFactory compatibility

The portable interface supplies JMH 1.37 OutputFormatFactory and its public
constructor and createFormatInstance(PrintStream, VerboseMode) method. JVM uses
upstream JMH. Both canonical imports and the Scala facade are available:

```scala
import scala.benchmark.*
val output = OutputFormatFactory.createFormatInstance(System.out, VerboseMode.NORMAL)
val results = new Runner(options, output).run()
```

NORMAL and EXTRA render benchmark metadata, warmup/measurement iteration starts,
primary results, aligned measurement secondary results and extended benchmark
results. Warmup output excludes secondary results. Iteration starts and completed
results flush the supplied stream. EXTRA additionally enables verbosePrintln.
The final summary delegates to the portable TEXT result formatter and requires
the runtime on the classpath.

SILENT ignores text output and lifecycle callbacks, including null callback data.
Raw byte writes and explicit flush still reach the caller's stream, as in JMH.
JVM startup diagnostics can therefore still appear in SILENT output. JMH
separately forwards benchmark stdout/stderr to System.out/System.err through its
binary link. Portable execution now preserves that channel distinction; see
[benchmark output channel compatibility](STDIO_PARITY.md) for transport and
host-acceptance limits.
All modes leave ownership with the caller: close does not flush or close that
stream. Null verbosity fails at factory creation; null streams fail when actually
used. Sink failures propagate rather than being swallowed by the formatter.

The portable headers preserve supplied BenchmarkParams, batch/time/timeout
settings, thread/group distributions, synchronization warnings and parameters.
JVM-specific VM-support classification and compiler-hint diagnostics have no
portable equivalent and are omitted. Final summary presentation uses this
project's existing report renderer, without the JVM compiler/profiler advice
preamble. These presentation differences do not change observations or results.

The shared output-factory fixture produces 111 transcripts per backend. It checks
all modes and verbosity levels, empty/nonempty warmup/measurement configurations,
thread/group pluralization, synchronized/unsynchronized headers, timeout warnings,
secondary alignment, null handling, raw writes, stream ownership and sink errors.
Actual Runner execution covers six embedded/forked configurations plus listing,
without comparing measured timings. Portable verification compares 87 normalized
transcripts per backend with upstream JVM JMH: supported metadata headers,
iteration text, single/three-observation extended results and direct writes. Runtime-specific listing and
run output are checked separately; final summary tables use adapted layouts.

The oracle also fixes SingleShotResult.extendedInfo to use distribution output,
including suppression for one or two observations. AggregationPolicy labels and
its AVG/SUM/MAX/MIN declaration order also match upstream, correcting the
three-observation extended result output.

Final local commands, source hashes and outcomes are recorded in
REMAINING_PARITY_WORK.md. Profilers and emulation of JVM compiler hints remain
outside the portable contract.

Primary references: [OutputFormatFactory](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/runner/format/OutputFormatFactory.java),
[SilentFormat](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/runner/format/SilentFormat.java),
[TextReportFormat](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/runner/format/TextReportFormat.java).
Ported code retains its GPLv2 with Classpath Exception notice.
