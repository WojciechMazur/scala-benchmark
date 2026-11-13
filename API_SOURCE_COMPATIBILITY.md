# Result and source compatibility checkpoint

This checkpoint extends the non-JVM JMH 1.37 API using shared fixtures that also
compile and run against upstream JVM JMH. It does not claim that every public
JMH utility or JVM internal has been ported.

Implemented additions and corrections:

- `TextResult`, `SingletonStatistics`, `RawResults` and `BenchmarkTaskResult`.
- `Multiset`, `DelegatingMultiset`, `HashMultiset`, `TreeMultiset`, `Multisets`,
  `BoundedPriorityQueue` and `HashsetMultimap`, including live views and upstream
  mutation behavior.
- `Result.of`, protected derivative extension methods, the generic `Aggregator`
  bound, final score-unit access and canonical result labels/units. Single-value
  standard result constructors and worker observations use SingletonStatistics.
- `ScoreFormatter`, result strings and simple/distribution extended information,
  including precision/histogram properties. Property parsing preserves decimal,
  hexadecimal and octal forms without depending on JVM-only property helpers.
- Multiset observation cancellation, NaN percentile behavior and Optional
  hashing/equality.
- runSingle preflight before mode overrides, plus parameter ordinal transport and
  injected metadata so overridden/repeated parameter runs remain distinct.
- Scala facade exports for results/statistics and custom-format Runner overloads
  on JVM and portable backends. JVM-only static companion terms expose TimeValue,
  VerboseMode, WarmupMode, ResultFormatType and the RunResult comparator; portable
  exports preserve canonical symbol identity for mixed wildcard imports.

The shared `result-extensions` fixture verifies protected subclass APIs, custom
aggregation/derivatives, text aggregation, raw/task containers, singleton and
multiset statistics, collection views, formatting and exceptional cases.
Existing `api` fixtures continue to cover state definitions inside objects,
overloads in separate groups, public collections and programmatic execution.

Primary references are the [JMH 1.37 results sources](https://github.com/openjdk/jmh/tree/1.37/jmh-core/src/main/java/org/openjdk/jmh/results)
and [utility sources](https://github.com/openjdk/jmh/tree/1.37/jmh-core/src/main/java/org/openjdk/jmh/util).
Ported code retains GPLv2 with Classpath Exception notices.

The [result-format factory checkpoint](RESULT_FORMAT_FACTORY_PARITY.md) adds
ResultFormat/ResultFormatFactory, standalone public-result rendering and JMH-style
values() calls on portable enums. It also covers JSON raw-data settings and the
missing high-percentile fields.

The [standard output factory checkpoint](OUTPUT_FACTORY_PARITY.md) adds normal,
extra and silent OutputFormatFactory instances, including caller stream ownership
and Runner integration. It also corrects SingleShotResult extended information
and AggregationPolicy labels/declaration order. JVM-only diagnostics and final table presentation are
explicitly distinguished from portable lifecycle and metric behavior.

Remaining API audit areas include
other utility classes, detailed command-line support APIs, exact Commons Math
exception types and less common extension contracts. Worker stdout/stderr
provenance and routing are implemented in the
[benchmark output channel checkpoint](STDIO_PARITY.md): user output bypasses
formatters and preserves separate System channels. Unsupported JVM internals,
compiler controls without equivalents and profilers remain outside the stated
portable contract. Host acceptance is tracked separately in CAPABILITY_MATRIX.md.
