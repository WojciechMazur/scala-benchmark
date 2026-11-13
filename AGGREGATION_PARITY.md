# Aggregation and counter parity checkpoint

The local checks compare the same Scala fixture against upstream JMH 1.37 on JVM
and the portable result APIs on Native, JS and Wasm. The fixture records 187
observations per backend. Synthetic worker durations make result arithmetic
deterministic; actual benchmark timing values are not compared across runtimes.

The covered cases include:

- Unequal worker populations and forks containing one, three and two iterations.
- Throughput, average time, single-shot time and pooled sample-time observations.
- Scalar AVG/SUM/MIN/MAX policies, missing secondary observations, benchmark-level
  primary/secondary additions, subsequent mutations and empty runs.
- Aggregator reuse and validation of incompatible labels, units and policies.
- Cancellation-prone sums, nearby large values, NaN, infinity, signed-zero inputs
  constructed from raw bits, and an exact population of 9,007,199,254,740,993.
- All six primitive numeric counter types and their boxed counterparts, plus a
  getter with observable reads. Warmup and last-iteration/trial teardown phases
  are checked independently of measurement timing.
- Skipped OPERATIONS getters in sample/single-shot modes, zero operation rates,
  infinite average-time counters, concrete public result types and raw throughput
  SUM policy.
- Counter values in text, CSV, SCSV, JSON, HTML and LaTeX; JSON infinity encoding
  uses JMH's `+INF`/`-INF` tokens.

This work reproduced and corrected these differences:

| Finding | Resulting behavior |
| --- | --- |
| Missing OPERATIONS observations contributed zero to run averages. | They are omitted, as these result types supply no zero result. |
| Missing EVENTS observations appeared as invented zero entries in raw data. | Raw arrays remain sparse; zero padding is used only for the aggregate statistics, matching JMH's ScalarResult behavior. |
| Benchmark-level primary results were ignored, run merging dropped benchmark-level results, and cached aggregates became stale. | Merging retains both levels and observes later additions through the public APIs. |
| Scalar aggregators retained the prototype's label/unit/policy and accepted inconsistent inputs. | Aggregation derives attributes from the supplied observations and rejects inconsistent inputs with IllegalStateException. |
| List and multiset statistics were treated interchangeably. | Ordinary metrics use list statistics, samples use pooled multiset statistics, and percentile/variance edge behavior follows the respective upstream implementation. |
| Scala vars were reset as Java public fields. | Scala accessors follow JVM JMH getter semantics. Java field resets remain distinct. |
| JS skipped warmup getter reads; final getter reads were duplicated. | Getters run once per iteration, with the final read after trial teardown. |
| Omitted OPERATIONS getters were still evaluated. | Mode filtering occurs before getter evaluation, and omitted observations are excluded from transport/results. |
| Public OPERATIONS metrics used scalar result objects. | They retain ThroughputResult/AverageTimeResult and the corresponding aggregation policies. |

The source references are upstream
[RunResult](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/results/RunResult.java),
[BenchmarkResult](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/results/BenchmarkResult.java),
[ScalarResult](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/results/ScalarResult.java),
[statistics implementations](https://github.com/openjdk/jmh/tree/1.37/jmh-core/src/main/java/org/openjdk/jmh/util),
[StateObjectHandler](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/generators/core/StateObjectHandler.java)
and [JSONResultFormat](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/results/format/JSONResultFormat.java).

Score/mean/variance/percentile comparisons retain a relative numeric tolerance.
Confidence intervals and errors additionally allow the propagated absolute
quantile accuracy of Commons Math's default inverse-CDF solver (1e-9), scaled by
standard deviation/sqrt(N). This allowance does not apply to scores or counts.
A separate test checks the portable quantile implementation against a tighter
reference solver. Exact long populations are compared as strings.

Local verification on 8 November 2026 passed 46 runtime tests, 30 compiler tests,
all four aggregation suites, and the execution/report regression suites. Each
backend also exercises real fork transport and public Runner results. The compiler
plugin compiled across all nine supported Scala versions; the 25 compiler tests
passed on both the oldest and newest supported versions.

Evidence is in `target/execution-parity/aggregation/<backend>`: observations,
empty difference files for the portable backends, execution logs, counter reports,
public API probe results and source/dependency fingerprints. Reproductions are
retained under `baseline`. The JVM reference captures benchmark stdout directly;
JMH's `-o` report stream does not include those fixture observations.

Run the oracle before the portable suites:

```sh
sbt --batch 'sbtPlugin/scripted aggregation/jvm; sbtPlugin/scripted aggregation/js aggregation/native aggregation/wasm'
```

This checkpoint does not establish exhaustive statistics/extension API parity,
all collection-mutation semantics, arbitrary grouped counter/failure interactions,
or Linux/Windows outcomes. Profilers remain outside the parity scope.
