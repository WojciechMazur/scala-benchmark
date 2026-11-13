# Execution semantics and measurement soundness plan

Status: implementation in progress, 8 November 2026. This document expands
milestone 4 of [PARITY_PLAN.md](PARITY_PLAN.md). Verified behavior is scoped by the
local checkpoint below; outstanding acceptance work is retained.

## Objective and acceptance contract

Establish that each reported score has the intended benchmark work, timed region,
operation denominator, worker population, iteration/fork population and aggregation
policy. A successful run must not silently include warmup, fixture time that JMH
excludes, unfinished workers, failed work or damaged transport data.

Use upstream JMH **1.37** as the semantic reference. Keep JVM measurements in JMH;
verify the generated Native/JS/Wasm harness against it. JS/Wasm remains one worker;
process isolation must not stand in for shared concurrent state.

There are two distinct obligations:

1. **Execution parity:** lifecycle, state identity, control transitions, counting,
   sampling and aggregation agree with the reference for supported configurations.
2. **Measurement integrity:** optimized code retains the workload and consumption,
   clocks/units are handled correctly, failures cannot yield a successful invalid
   result, and public results/reports preserve the observations.

Matching timing values across JVM, Native, JS and Wasm is not an acceptance test.
Passing harness tests cannot establish that an arbitrary user's workload or host
setup is a good experiment. Document supported semantics and observed limitations
without claiming that a confidence interval detects systematic harness bias.

## Existing evidence and gaps

The recorded macOS acceptance run passed 24 compiler tests, 21 runtime/selection
tests, all eight platform/report scripted suites and compilation with nine Scala
versions. Existing tests cover basic normalization, loop/fixture counts, state
identity, groups, counters, worker failure, warmup and transport/report behavior.
This is a useful regression baseline, not an exhaustive execution proof.

Much of the current loop coverage asserts expectations written alongside our
implementation. Extend it with independent upstream evidence and tests that can
reject plausible incorrect implementations. Native Linux/Windows outcomes still
need to be established; configured CI jobs are not evidence of passing runs.

### Reproductions and fixes

The initial source-inspection findings were reproduced before changing their
behavior. Baseline failure logs are retained under `target/execution-parity`.

| ID | Reproduced finding | Local evidence |
| --- | --- | --- |
| R1 | Sampling target/stride rounding differed for sub-millisecond durations and adaptation boundaries. | Fixed; generated-stub comparisons cover boundary durations, seeds, initial masks and large durations. |
| R2 | Zero accumulated invocation time selected zero instead of JMH's enclosing-interval fallback. | Fixed; scripted-clock fixture traces and complete harness scores match upstream. |
| R3 | Extra worker barriers prevented catchup while peers ran iteration setup; JS/Wasm unsynchronized timers started after setup. | Fixed; upstream/portable forced-scheduling probes and actual four-backend readiness fixtures pass. |
| R4 | Later user epilogues ran after failed setup/work although JMH skipped them. | Fixed; every single-worker lifecycle failure is compared with generated JMH and exercised on four backends. |
| R5 | Fork progress used wall-clock deadlines. | Replaced with monotonic, sequenced leases; Scala/Node parser/budget tests and real Node timeout/interruption/missing-result tests pass. |
| R6 | Adapted sampling masks carried into later iterations although JMH 1.37's static stub updates only its by-value parameter. | Fixed; repeated-iteration retained histograms match the same upstream harness instance. |
| R7 | Normalized operation metadata used integer multiplication/division, differing from upstream rounding and overflowing for large counts. | Fixed; normalization statements extracted from generated JMH validate large counts, rounding and saturation. Exact raw invocation counts are preserved separately; sampled-batch normalization has its own generated-code reference. |
| R8 | Inherited teardown helpers were reversed rather than following the reflection generator's qualified-name order. | Reproduced and fixed; compare equivalent Java/Scala diamond graphs, including a superclass sorting after its subclass. |
| R9 | Hidden annotated declarations were dropped when a fixture was overridden. | Reproduced and fixed; annotated and unannotated overrides retain the calls emitted by JMH, including virtual dispatch. |
| R10 | A peer could retry a failed shared fixture after acquiring its released lock. | Reproduced and fixed; failures are retained before unlocking, and peers receive the original cause. |
| R11 | A peer could retry failed shared construction. | Reproduced and fixed; constructor/parameter-assignment errors are published before releasing the shared construction lock. |
| R12 | A consumed returned allocation does not necessarily preserve its never-read payload fields on JS/Wasm. | Retained counterexample and documented limitation. Positive allocation checks make payload use observable outside measurement and inspect the resulting optimized stores; no general reflective traversal is inserted into benchmarks. |
| R13 | Continue-on-error saved later successful results but then threw an overall exception. | Reproduced against JMH and fixed in the shared runner; CLI and public APIs return only successful configurations, with caller cancellation and surviving workers remaining fatal. |
| R14 | Caught forked benchmark failures were treated like process crashes, losing JMH's valid-prefix retention. | Fixed; 24 independent sequence/report outcomes match JMH on all four backends, including nullable metadata for incomplete trials. |
| R15 | A blocked output relay could trap cleanup or mask the primary failure. | Fixed for fork relays; bounded Native joins/quarantine and killable Node replay pass linked pipe-blocking checks while preserving earlier valid reports. |

Additional reference cases also corrected sampled-batch operation-metadata
rounding and the separate invocation/sample clock intervals in SampleTime.
Parent-side score validation uses a relative comparison so tiny values in large
output units cannot pass an unrelated absolute tolerance.

### Local implementation checkpoint

- **E0:** generated JMH reference stubs/full harnesses, fixed clocks/seeds, source
  and artifact fingerprints, normalized observations and retained failure evidence
  are implemented. The case inventory is still narrower than every planned state
  graph and scheduling interaction.
- **E1:** all modes, fixture combinations, batch/OPI/unit combinations, coarse
  clocks and large normalized operation counts have independent reference checks.
- **E2:** readiness/catchup and pre-setup rendezvous were corrected; group worker
  distributions and existing state-injection fixtures pass. Equivalent Java/Scala
  diamond graphs now compare all three sharing scopes, both grouped members,
  one/two groups, repeated iterations, warmup, batching, inherited declarations
  and annotated/unannotated overrides. Shared constructors and trial/iteration
  fixtures have peer-failure comparisons, now including shared Benchmark/Group
  invocation setup/teardown. Linked grouped failures exercise timed readiness,
  cooperative release and non-returning-peer containment. Wider graph shapes
  and the full timed-mode shared-fixture interaction matrix remain open.
- **E3:** upstream comparisons cover unequal worker and ragged iteration/fork
  populations, groups, missing secondaries, all scalar policies, weighted samples,
  percentiles and confidence intervals. The 187-observation shared API fixture
  also covers benchmark-level additions/mutations, non-finite/signed-zero inputs
  and exact long populations above 2^53. Numeric/boxed counter lifecycle, omitted
  getters, concrete public result types and all six counter reports pass locally.
  See [AGGREGATION_PARITY.md](AGGREGATION_PARITY.md). Exhaustive utility/extension,
  collection-mutation and broader grouped counter/failure interactions remain open;
  selected counter-capture failures and partial-iteration rejection now pass.
- **E4:** failure probes cover every lifecycle boundary, warmup, synchronization
  and cooperative peer release. Caller interruption, bounded shutdown and a
  separate-process uncooperative-worker quarantine probe pass. Node supervisor
  signals terminate its worker. Worker lifelines now cover hard-killed Native
  coordinators and Node coordinators/supervisors, including blocked synchronous
  JS/Wasm and supervisor startup after owner loss. Native fork cancellation
  preserves interruption, bounds forced-stop waiting and quarantines surviving
  processes. The 39 linked fault cases and 11 Node host tests are documented in
  [FORK_CONTAINMENT.md](FORK_CONTAINMENT.md). Arbitrary benchmark-created process
  trees, stopped-process combinations and cross-OS acceptance remain open;
  in-process JS cannot forcibly stop uncooperative synchronous code. The grouped
  failure/recovery extension is documented in
  [GROUP_FAILURE_PARITY.md](GROUP_FAILURE_PARITY.md).
- **E5:** fork data has parent-side validity/consistency checks, full binary and
  JS long-count round trips, and bulk-warmup/report integration coverage. The
  fork/transport follow-up compares warmup/later-fork failures with JMH, retaining
  validated prefixes only for explicit benchmark failures. Corrupt/crashed forks
  discard the configuration. Linked truncation, incomplete/trailing data, FIFO,
  interrupted serializer and blocked-output probes now pass. See
  [FORK_TRANSPORT_PARITY.md](FORK_TRANSPORT_PARITY.md). Embedded late failures,
  bulk warmup-action failures and the broader interaction matrix remain open.
- **E6:** JVM generated bytecode is inspected for retained work/consumption and
  direct clock reads. Actual optimized Native/JS/Wasm fixtures run locally.
  The optimized-code follow-up adds final Native disassembly/LLVM checks, full-link
  JS/Wasm host observations, and WAT inspection of representative arithmetic,
  allocations, consumption and clocks. See [OPTIMIZATION_INTEGRITY.md](OPTIMIZATION_INTEGRITY.md)
  for the unused-field counterexample and the scope of these checks. All five
  local suites (Native no/thin/full LTO, JS and Wasm) pass, including normal and
  observed JS/Wasm runs. Wider workload,
  GC/optimization combinations and Linux/Windows acceptance remain open.

Evidence locations:

- `target/execution-parity/oracle/<run>/<fixture>`: upstream originals, test-only
  transformed sources, provenance and expected/actual loop/result/failure records.
- `target/execution-parity/platforms/<backend>`: fixture/library/compiler-plugin
  fingerprints plus actual readiness and lifecycle result JSON.
- `compiler-plugin/target/execution-parity/codegen/<compiler-jar>`: generated
  classes and `javap` inspection output.
- `target/execution-parity/integrity/<backend-or-variant>`: executed optimized
  artifacts, selected code/disassembly, host observations, inspector outcomes,
  result JSON and source/artifact fingerprints.
- `target/execution-parity/integrity-limitations/unused-payload`: original fixture,
  linked JS/WAT and provenance showing the never-read field's removal.
- `compiler-plugin/target/execution-parity/state-graphs/<run>`: equivalent graph
  sources, actual JMH/portable generated classes, SHA-256 provenance and normalized
  state identity/lifecycle comparisons. Separate loaders isolate failed JMH trials;
  they must not be reused as fresh trials after failure.

References for R1-R4: upstream [BenchmarkGenerator](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/generators/core/BenchmarkGenerator.java),
[RawResults](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/results/RawResults.java),
[BenchmarkHandler](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/runner/BenchmarkHandler.java)
and [InfraControl](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/runner/InfraControl.java).

## Ordered work packages

### E0. Establish an independent reference and evidence format

**Deliverables**

- Pin core/generator artifacts and source revision to JMH 1.37; record artifact
  hashes, source revision, fixture source hash and compiler/linker/host settings.
- Build a shared fixture driver with two execution paths: the upstream JVM
  generated harness and this project's generated harnesses. Keep reference
  expectations independent of runtime implementation helpers.
- Define normalized observations: benchmark/configuration, logical state ID,
  worker/group/subgroup, iteration type/index, lifecycle event, raw call counts,
  normalized operations, elapsed interval, histogram, counters and failures.
- Preserve generated harness sources/IR and normalized expected/actual records
  as failure artifacts. Resolve the recorded configuration explicitly.
- Map every requirement to an upstream reference, a fixture, supported backends,
  current evidence and status: unverified, reproduced mismatch, fixed or verified.

**Test design rules**

- Compare per-worker sequences and required happens-before edges, not a single
  total event ordering across concurrently scheduled workers.
- Compare exact counts for bounded work such as single-shot batches. For timed
  loops, compare counts to independently observed work within that same run;
  do not require equal invocation counts between backends.
- Validate arithmetic separately using supplied observations and upstream result
  classes. Do not derive the expected result using our aggregation function.
- Instrumented traces establish semantics; run separate uninstrumented optimized
  fixtures to establish workload retention. Trace collection changes execution cost.
- Any test-only clock/seed/stop seam must disappear from production hot loops.
  Inspect generated output to ensure it adds no virtual clock calls, closures,
  allocation, locks or per-invocation reporting to ordinary measurements.

**Exit gate:** the driver detects a deliberately wrong operation count, fixture
order, state alias and histogram observation. Test tooling failures cannot be
reported as benchmark success.

### E1. Prove timed regions, operation counts and clock handling

**Targets:** `Loops.scala`, `Measurement.scala`, both `Execution.scala` variants,
`Infrastructure.scala` and compiler-generated invocation code.

- Specify all four modes separately: actual calls per batch, logical operations,
  OPI, warmup/measurement batch sizes, measured/all-operation metadata and units.
- Verify invocation setup/teardown placement, fixture overhead exclusion,
  catchup/cooldown calls and last-iteration trial teardown relative to sampling.
- Cover no fixtures, setup-only, teardown-only and both; include different
  warmup and measurement batch sizes and OPI values that do not divide counts.
- Test zero-duration configuration, already-signalled stop, very short work,
  zero measured invocation time, incomplete single-shot batches and exceptions.
- Verify monotonic elapsed calculation and deadline arithmetic, including zero,
  large durations, overflow boundaries and conversion/rounding behavior.
- Check large operation counts without running huge loops: near 2^53 and integer
  boundaries; preserve reference rounding and prevent silent wrapped metadata.

**Exit gate:** independently computed denominators, timed-region boundaries and
units agree with upstream for each supported mode/configuration. R2 is resolved.

### E2. Prove lifecycle, state sharing and synchronization

**Targets:** compiler state-instance graph, `StateSlot`, `Environment.guarded`,
worker assignment, Native coordinator and JS control/deadline handling.

- Exercise Trial/Iteration/Invocation fixtures, inheritance, dependency chains
  and diamonds, repeated injection sites, and state construction/assignment order.
- Verify Benchmark/Group/Thread identity across workers, groups, members,
  iterations and separate trials/forks. Preserve distinct Thread injections.
- Cover unequal member counts, multiple groups, rounded worker counts, zero-worker
  members and one active group member on JS/Wasm.
- Force a worker to arrive late at setup, measured entry, measured exit and teardown
  using test barriers/latches. Establish which transitions require synchronization
  and which remain independent with `-si false`.
- Check exactly-once shared fixture execution, safe publication, lock release on
  exceptions and dependency lifetime. Do not serialize ordinary benchmark work
  while arbitrating shared fixtures.
- Check `Control` start/stop transitions and BenchmarkParams/IterationParams
  identity throughout lifecycle events, including single-shot and warmup-only runs.

**Exit gate:** required partial orders, identity relationships and lifecycle
cardinalities match upstream under forced scheduling. R3 is resolved; repeated
bounded stress runs expose neither races nor deadlocks.

### E3. Prove sampling, counters and statistical aggregation

**Targets:** sampling loop/state, `SamplingHistogram`, `Reports.scala`,
`PublicResults.scala` and public result/statistics containers.

- Resolve R1 with fixed seeds and synthetic timestamps: sampling decisions,
  mask persistence, stride thresholds, target rounding, histogram quantization,
  halving and merging across workers/iterations/forks.
- Verify sample-time normalization and invocation fixtures for batch/OPI
  combinations; distinguish observed samples from total benchmark invocations.
- Verify AuxCounters reset timing and capture after iteration/trial teardown,
  numeric types, zero values, getter/field differences, group/member labels,
  unsupported modes, units and OPERATIONS versus EVENTS policies.
- Use unequal worker rates and unequal observation populations to expose incorrect
  averaging or weighting. Verify throughput summation, other mode policies,
  group/member primaries, counter totals, pooled samples and percentile derivatives.
- Test variance, confidence intervals, sample counts and fork/iteration boundaries
  against upstream with deterministic data, including empty/one/two observations,
  zeros and permitted non-finite observations. Use declared numeric tolerances.
- Verify built-in result classes and custom aggregators, missing secondary
  observations, zero-result padding and benchmark-level secondary overrides.

**Exit gate:** public primary/secondary values and statistics reproduce upstream
for supplied raw observations. No averaging of percentiles replaces the required
histogram aggregation; metadata counts and score normalization remain distinct.

### E4. Prove failure containment, timeouts and cancellation

**Targets:** both execution coordinators, fixture arbitration, runner failure
handling, Native process transport and Node supervisor.

- Inject failures in construction, parameter assignment, each setup/teardown
  level, benchmark work, counter capture and serialization. Test primary failure
  plus cleanup failure, and one worker failing while peers are blocked or running.
- Match JMH's cleanup and failure/result-retention contract where supported;
  document any necessary backend difference. Resolve R4.
- Test caller interruption/cancellation, cooperative blocking, non-returning work,
  blocked fixtures, startup/measurement/teardown stalls, child crashes and missing
  or malformed progress/result data. Resolve R5.
- Make coordinator waits and shutdown budgets bounded as a whole; avoid multiplying
  a nominal timeout by workers or lifecycle stages without an explicit contract.
- Assert failed/incomplete trials cannot emit a successful benchmark result.
  Preserve the upstream fail-on-error/continue behavior for other configurations.
- Do not promise safe forced termination of in-process threads or JS code. For
  uncooperative code, use a supervised process; fail clearly when a requested
  containment guarantee cannot be provided. A contaminated in-process run must
  not continue into later measurements as though isolation were restored.
- Run hang/cancellation tests under an independent outer watchdog and check child,
  worker, relay and temporary-file cleanup on Linux, macOS and Windows.

**Exit gate:** failures cannot deadlock the test driver or silently contaminate
later results; surviving work and unsupported containment are visible failures.

### E5. Prove warmup/fork isolation and end-to-end result integrity

**Targets:** `BenchmarkRunner`, `WarmupRequest`, both fork protocols and all reports.

- Trace INDI/BULK/BULK_INDI, extra warmup selection, warmup-only execution, warmup
  forks and measurement forks. Verify execution order and state/static lifetimes
  against upstream, including multiple benchmarks and parameter combinations.
- Verify warmup observations/counters/sample state do not accidentally become
  measurement results; retain only state that JMH intentionally carries forward.
- Check forks=0 separately from process isolation; never imply their equivalence.
- Round-trip every resolved execution option and raw field: large counts, worker
  identities, elapsed time, histograms, counters and trial metadata. Preserve
  counts above JavaScript's exact Number range and reject corrupt/truncated data.
- Compare internal observations, public results and JSON, then verify text, CSV,
  SCSV, HTML and LaTeX contain the same metrics/units/populations within rendering
  precision. Reports must not silently reconstruct a different score.

**Exit gate:** every emitted metric is traceable to retained measurement
observations with the same identity and configuration before and after a fork.

### E6. Verify optimized workloads and establish the release gate

- Audit primitive/reference/unit return consumption, explicit Blackhole use,
  Native barriers and JS/Wasm host calls under production optimization settings.
- Inspect generated JVM harness/Native IR/optimized JS/Wasm artifacts for representative
  pure computations and returned allocations. Establish that result consumption
  remains observable and the measured call remains in the loop. Document the
  boundary for user benchmarks that return Unit and have no observable effect.
- Check generated hot loops retain intended direct calls/inlining and avoid
  accidental reflection, per-invocation allocation or global serialization.
  These checks demonstrate retained structure, not equality of machine code.
- Run bounded multiworker stress on actual Native Linux/macOS/Windows and
  supported optimization/LTO configurations; execute actual JS and Wasm artifacts.
- Run compiler compatibility checks across the supported Scala matrix, with
  representative generated-code execution on the oldest/newest supported versions.
- Require clean/fresh artifact provenance. Reset `++` before scripted publication;
  retain the existing guard against stale locally published libraries.

**Exit gate:** all mandatory semantic, integrity and platform cases pass with
published evidence; unsupported cases reject clearly. Profilers and concurrent
JS/Wasm execution remain outside this gate.

The inheritance reference uses JMH 1.37's reflection generator:
[RFMethodInfo.compareTo](https://github.com/openjdk/jmh/blob/1.37/jmh-generator-reflection/src/main/java/org/openjdk/jmh/generators/reflection/RFMethodInfo.java)
and [StateObjectHandler](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/generators/core/StateObjectHandler.java).
Helper order is matched to that generator's observed output; no universal order
across other JMH generator implementations is claimed.

## Coverage strategy and CI

Use a bounded deterministic core covering every mode, fixture presence, batch/OPI
boundary, synchronization policy, worker/state scope and execution path. Add
pairwise coverage for the larger configuration space and mandatory interaction
cases rather than relying on pairwise generation alone:

- Sampling + invocation fixtures + batch > 1 + OPI > 1.
- Multiple Native groups + shared fixtures + asymmetric/disabled members.
- Unsynchronized iteration + slow fixture + short duration.
- Worker failure during shared setup or catchup/cooldown.
- Teardown failure + counter capture + final measurement iteration.
- Bulk warmup + parameters + multiple forks + state/sample persistence.

Keep fast reference/math/trace tests on every change. Run platform execution and
bounded failure tests in required CI. Put larger seeded scheduling repetitions
and optimized-code audits in scheduled/release checks, with reproducible seeds,
configuration and artifacts. Critical reference mismatches must not be hidden by
retries, skipped tests or a wider timing tolerance. Regressions become permanent
fixtures before changing production behavior.

Maintain a case inventory with columns: ID, semantic requirement, reference,
fixture, backend/configuration, expected relation, observed result, artifact link
and status. No checklist item becomes verified merely because it compiles or an
unrelated platform suite is green.

## Implementation order, effort and completion

| Sequence | Work | Planning range |
| --- | --- | --- |
| 1 | E0 reference/evidence driver and minimal R1-R5 reproductions | 2 days |
| 2 | E1 timing/counting and E2 lifecycle/synchronization | 5-8 days |
| 3 | E3 sampling/counters/statistics | 2-4 days |
| 4 | E4 failure/timeout/cancellation | 3-5 days |
| 5 | E5 transport/warmup and E6 optimized/platform acceptance | 3-6 days |

Budget roughly **15-25 engineer-days** for this end-to-end assurance scope, then
re-estimate after the first reproductions. This is broader than the earlier
5-10 day estimate for focused runtime fixes: it includes building an independent
oracle, numerical/transport verification and optimized multi-platform acceptance.
Unexpected concurrency or process-containment defects are the main schedule risk.

Land changes in reviewable units: one reproduction, its production fix, upstream
comparison and affected backend checks. Start with R1/R2 and the E0 driver, then
address readiness/cleanup before treating stress results as evidence of correctness.
Update `WORK_SUMMARY.md` only with behavior actually implemented and verified.
