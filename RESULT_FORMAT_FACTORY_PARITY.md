# Public result-format factories and enum calls

The portable interface now supplies JMH 1.37 `ResultFormat` and both
`ResultFormatFactory.getInstance` overloads. The JVM uses upstream JMH. The Scala
facade exposes the same API on all backends.

The stream overload leaves flushing and closing to the caller. The file overload
opens/truncates lazily at writeOut, writes UTF-8 and overwrites on reuse. File
errors become IllegalStateException; null arguments retain their relevant eager
or deferred failure boundary. Portable file destinations follow the existing
runtime's host file constraints.

Portable formatting requires the benchmark runtime on the application classpath.
Formatting delegates through the interface/runtime service boundary, without
benchmark discovery or general reflection. Manually constructed results can name
benchmarks absent from the generated inventory. Public scores, errors, confidence
intervals, units, secondary results and fork/iteration populations remain the
source of report data. Sampled primary results retain observation histograms.
Supplied BenchmarkParams provide the JVM/VM/version and configuration metadata.
No worker timing or clock calibration is synthesized for imported results; host
clock metadata is omitted when no calibration is known. The callback report path
uses the same public-result adapter and retains its known runtime calibration.

All six existing portable report renderers are available (TEXT, CSV, SCSV, JSON,
LATEX and HTML). Upstream JMH has five public enum values. Portable presentation
continues to use this project's established renderers; byte-for-byte upstream
text layout is not promised. JSON now includes the high-percentile fields
99.999 and 99.9999. `jmh.json.rawData` is read once when a stream JSON formatter is created (or a file formatter first
writes, even an empty collection),
suppresses primary raw data and leaves secondary observations intact, as in JMH.

Portable public enums accept both Scala `values` and JMH-style `values()` calls.
The additional empty-argument method has a distinct target name so the
compiler-generated Scala method remains intact. Each call returns a new array
containing the existing enum cases. This includes annotations, nested compiler
control/counter enums, runner enums, result roles/policies and result formats;
Scala facade exports preserve the overloads.

The shared formatters fixture uses two forks with two manually built iterations
in each of the four modes. It checks every format, stream ownership, repeated
writes, file failures, supplied metadata, sampled histograms, secondary metrics,
empty collections, raw-data suppression and enum calls against upstream JVM JMH.
Final local acceptance is recorded in REMAINING_PARITY_WORK.md. Normal, extra
and silent OutputFormatFactory support is documented in OUTPUT_FACTORY_PARITY.md.

Primary references: [ResultFormatFactory](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/results/format/ResultFormatFactory.java),
[JSONResultFormat](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/results/format/JSONResultFormat.java).
The ported API retains its GPLv2 with Classpath Exception notice.
