# Remaining parity execution ledger

User-authorized order (local verification only; no commits or pushes):

1. Embedded late failures and bulk warmup-action failures: implemented; 52 oracle cases pass on JVM/Native/JS/Wasm. Late embedded metadata failure remains a clear fatal error (upstream throws IndexOutOfBoundsException).
2. Combined grouped/counter/accounting/cancellation faults: expanded; 42 additional fault executions plus disabled-OPERATIONS probes pass on JVM and Native.
3. General output/process-tree cleanup and repeated cancellation: implemented for tested POSIX hosts; 15 new cases per Native/JS/Wasm pass, alongside existing containment. Windows Native uses jobs but awaits Windows execution; escaping POSIX sessions and blocked custom JS callbacks remain limits.
4. Host acceptance: Linux aarch64 Docker passes 55 runtime and 31 compiler/reference tests, two JVM oracle suites and six Native integration suites. Linux strict C declarations fixed and rerun. Windows-target C compilation passes; user confirmed no local Windows environment, so actual Windows execution stays unverified.
5. API/source checkpoint: statistics edge behavior, result collection aliasing/mutation, multimap helpers, object-scoped states and overloads in separate groups implemented. Shared API suites pass on JVM/Native/JS/Wasm. Custom OutputFormat callbacks were open at this checkpoint and are implemented in the later checkpoint below; the broader extension audit continues.
6. Configuration/integrity checkpoint: repeated parameters, mode order, partial inherited annotations and grouped annotation collisions corrected. Four-backend configuration suites pass. All nine compiler versions compile; 33 default compiler/reference tests and 28 tests each on the oldest/newest compilers pass. GC-enabled optimized integrity checks pass on Native none/thin/full, JS and Wasm. CAPABILITY_MATRIX.md distinguishes verified, implemented and open coverage. CI changes prepared locally, not executed remotely.

Each checkpoint must record implementation, independent reference expectations,
local verification and unresolved limits. Do not count unavailable host acceptance
as a pass. Profilers and unsupported concurrent JS/Wasm execution stay excluded.


Verification logs for this checkpoint (local, no commits/pushes):

- `/tmp/benchmark-remaining-acceptance.log`: API suites and all three containment suites pass; two configuration failures in this earlier run are superseded by the corrected run below.
- `/tmp/benchmark-remaining-config-final.log`: four configuration suites and both JVM/Native grouped-failure regressions pass.
- `/tmp/benchmark-remaining-compiler-final.log`: 33 default compiler/reference tests, all nine compiler compiles, oldest/newest 28 tests each pass.
- `/tmp/benchmark-remaining-integrity-none.log`: Native none, JS, Wasm with GC pass.
- `/tmp/benchmark-remaining-integrity-lto-final.log`: dedicated Native thin/full with GC pass (generic thin fixture attempt superseded).
- `/tmp/benchmark-remaining-node-final.log`: all 14 Node tests pass.
- `target/execution-parity/linux/acceptance.{log,json}`: final Linux checkpoint; the disposable container and its volume have been removed after retaining evidence.
- `/tmp/benchmark-remaining-output-reference-final.log`: six JVM custom OutputFormat callback/lifecycle traces pass. Portable integration remains open, with corrected asynchronous-fork delivery contract and implementation order in OUTPUT_FORMAT_PARITY_PLAN.md.


The custom-output reference caught an overly strong initial test assumption:
JMH fork callbacks are delivered asynchronously and may lag worker fixtures.
Only embedded callbacks have the tested callback-before-fixture ordering. A
portable implementation must record genuine ordered events (including warmup and
failed iteration starts), without adding worker/parent acknowledgments.


OutputFormat executor-event checkpoint (2026-11-09): the no-op-by-default
TrialObserver foundation now emits genuine starts and completed observations on
both executors. Shared checks pass 32 cases each on JVM/Native/JS/Wasm, covering
all modes, warmup-only trials, thirteen lifecycle faults, six callback faults,
final counter capture and warmup batch normalization. Each emitted successful
observation is validated using the existing fork-result checks. Enabled
Native/JVM observers pause workers outside timed phase barriers. Three JVM unit
tests include slow callbacks with two workers and cancellation at four callback
boundaries, preserving interruption and leaving no workers alive.

Verification: 58 runtime tests and 33 compiler/reference tests pass; the final
three observer unit tests and three portable suites pass. Evidence and source
hashes are retained in `target/execution-parity/output-format/observer/`, including
`runtime.log`, `observer-unit-final.log`, `portable-final.log`, `compiler-final.log`
and `verification.json`. CI definitions include the new suites; no remote jobs
were run. Public OutputFormat/service constructor wiring, fork event records and
run/benchmark callback integration remain open in OUTPUT_FORMAT_PARITY_PLAN.md.


Custom OutputFormat and source-compatibility implementation (2026-11-09):
public constructors/services, ordered Native/JS/Wasm fork records, run and
benchmark callbacks, listing, callback result identity and public report views
are now implemented. Native cancellation clears interruption during worker joins
and restores it afterward. Failed JS/Wasm forks retain their original diagnostic
output. All six portable report formats reflect callback-added metrics and edits
to the endRun collection. JVM measurement remains in upstream JMH.

The next API checkpoint adds text/raw/task results, singleton statistics,
multiset and collection helpers, protected derivative methods, heterogeneous
aggregators and score formatting. runSingle inventory preflight and parameter
ordinals follow the JVM reference, including repeated overridden values across
forks. Scala facade static terms use JVM-only companions so portable imports
retain the original symbol identities. See API_SOURCE_COMPATIBILITY.md.

This supersedes the earlier statements that public callback wiring and fork
transport were open. Windows runtime acceptance remains unavailable. Broader
source/API coverage is a continuing audit, not a claim of complete JMH parity.


Final acceptance for the custom-output/API checkpoint:

- `target/execution-parity/output-format/final/`: final macOS logs, source hashes
  and verification manifest. The four configuration, four custom-output and four
  result-extension suites pass. Output fixtures check 49 report cases on each
  portable backend and 41 against upstream JVM JMH, including mutation and
  clearing of the endRun collection. Native checks eight public cancellation
  boundaries and overridden concurrent groups.
- `target/execution-parity/linux-output-api/acceptance.{log,json}`: Linux aarch64
  passes 61 runtime tests, 33 compiler/reference tests, the JVM runner-failure
  oracle and Native API, custom-output, result-extension and runner-failure suites.
  All 424 source hashes match the workspace. The disposable container and volume
  were removed after retaining the logs, script and generated observations.
- An earlier Linux run lacked the JVM observations file required by the
  runner-failure comparison; it is superseded by the successful complete run.
- Actual Windows execution remains unverified because no local environment is
  available. No remote CI, commit or push was performed. The known deliberate
  callback-error policy and remaining public API gaps are documented separately.

The final macOS checkpoint passes 21 scripted integration suites (12 primary,
9 regressions), 61 runtime tests, 33 forced compiler/reference tests, all nine
compiler-version builds and 19 Node supervision tests. The regression set covers
API execution on all four backends, Native fork sequencing, JS/Wasm reports and
failed-fork diagnostics. `compilerPlugin/test` initially reused sbt's successful
quick-test cache; `compilerPlugin/testOnly *Test` explicitly reran all 33 cases.
Formatting, Node syntax, YAML parsing and `git diff --check` also pass.


Public result-format factory and enum source-call checkpoint (2026-11-09):
ResultFormat and both ResultFormatFactory overloads are implemented, using a
shared public-result report adapter. JSON preserves supplied metadata and raw
observations without inventing clock calibration; the high-percentile fields
and initialization timing of jmh.json.rawData match the tested JVM contract.
Portable enums support both values and values(), with fresh arrays of the same
cases. README.md and RESULT_FORMAT_FACTORY_PARITY.md document the API and limits.

Final macOS acceptance passes four formatter suites, six callback/report
regression suites, 61 runtime tests, 33 compiler/reference tests and interface
compilation on all nine supported Scala versions. The formatter fixture checks
every format in every mode plus stream/file ownership, lazy errors, empty writes,
raw-data settings and enum calls. All 116 generated reports are retained.

Final Linux aarch64 acceptance passes 61 runtime tests, 33 compiler/reference
tests and JVM/Native formatter suites. Its 445 source input hashes match the
workspace. Twenty full deterministic JSON comparisons across macOS and Linux
match the upstream JVM oracle, with relative numeric tolerance 1e-8 and absolute
tolerance 1e-10. These compare manually constructed observations, not actual
benchmark timing values.

Evidence: target/execution-parity/formatters/{verification.json,source-manifest.json,
formatters-final.log,regressions-final.log,compiler-final.log,oracle-comparison.json};
backend report files are under jvm/, native/, js/ and wasm/. Linux logs, source
hashes, command script and reports are under linux/. Earlier fixture path/Locale
failures and an interrupted link during severe host memory pressure are
superseded by the final successful runs. Linux was stopped during macOS Native
builds, then verified sequentially. Its disposable container and volume were
removed after retaining evidence; existing containers were untouched. No commits,
pushes or remote CI runs were performed. Windows execution remains unavailable.

Next API gaps: the standard OutputFormatFactory (normal/silent output), remaining
supporting/extension and command-line APIs, exact Commons Math exception types
and uncommon source shapes. Profilers and unsupported JVM internals stay excluded.


Standard OutputFormatFactory checkpoint (2026-11-09): normal, extra and silent
formats are implemented with canonical/facade entry points, benchmark headers,
iteration formatting and flushes, secondary alignment, extended results, raw
writes and caller stream ownership. JVM compiler/VM diagnostics are omitted on
portable backends; final summaries use the existing TEXT renderer.

The oracle additionally corrected SingleShotResult.extendedInfo to use
distribution output (empty for one/two observations) and AggregationPolicy
descriptive labels plus AVG/SUM/MAX/MIN declaration order. One/three-observation
formatter transcripts and an explicit two-observation assertion cover the fix.

Final acceptance passes four factory suites on JVM/Native/JS/Wasm and three
portable result-extension regressions. Each backend retains 111 transcripts;
portable verification performs 87 comparisons each against upstream JVM JMH
(261 macOS comparisons). Actual Runner checks cover six embedded/forked runs and
listing per backend, with direct stream/null/sink-failure tests. No actual timing
values are compared. All 61 runtime and 33 compiler/reference tests pass. Clean
interface builds pass on all nine supported Scala versions. An incremental
Scala 3.10 facade export cycle was observed; a clean compile and the full clean
matrix supersede that failed attempt, without production compiler workarounds.

Linux aarch64 Native passes its factory suite and 87 comparisons using retained
upstream JVM transcripts with supplied deterministic metadata. Its 466 input
hashes match the workspace. The disposable Linux container and volume were
removed after saving evidence. Existing containers were untouched. Evidence is
in target/execution-parity/output-factory/: verification.json, source-manifest.json,
final-restored.log, acceptance.log, unit-matrix.log, scala310-clean.log and per-backend
transcripts; Linux evidence is under linux/.

A separate worker stdio gap remains: JMH binary-link benchmark stdout/stderr
bypasses the supplied formatter and goes to System.out/System.err, while portable
capture uses its console wrapper. A relay experiment based on conflating this
with JVM startup diagnostics was reverted. Existing relay code/tests match the
passing pre-experiment hashes; the final suites republished/retested that source.
Reproducing the channel distinction requires a dedicated transport pass, not a
change to the factory's print/write policy. OUTPUT_FACTORY_PARITY.md records this
limit alongside the supported factory contract. Other supporting/extension APIs,
uncommon source shapes and exact Commons Math exception APIs remain open.
Windows execution is unavailable locally; no remote CI, commits or pushes ran.


Benchmark stdout/stderr channel checkpoint (2026-11-09): the separate user-output
transport is implemented; this supersedes the earlier open worker-stdio gap.
Native uses raw per-channel file relays; Node keeps three owned channels and
drains bounded chunks after synchronous forks. Embedded Scala Console preserves
ambient streams. User Scala/Java output bypasses formatters, `-o` and SILENT.
Bootstrap diagnostics use formatter raw writes, while harness text follows
formatter verbosity. Scope bindings remain outside timed loops. Cleanup drains
relays even if event replay fails, retains error context and caller ownership,
and handles the final EOF/append race. Default JS line streams are temporarily
adapted to bytes; caller-supplied System streams, including Console aliases,
remain in use. See STDIO_PARITY.md for precise transport and host limits.

Final macOS acceptance passes 64 runtime tests, 33 compiler/reference tests and
23 Node supervision tests. The 21-suite regression batch covers stdio on all
four backends, portable custom-format callbacks, four-backend runner failures
and fork sequences, plus portable containment and execution checks. The final
frozen-source run repeats all four stdio suites and JS/Wasm custom-format suites
after adding caller System/Console alias coverage. All pass. Portable stdio checks
perform 192 exact channel comparisons against upstream JVM JMH; the four
backends also pass eight standalone binary CLI runs and eight caller-stream
alias runs. No benchmark timing values are compared.

Evidence is retained in target/execution-parity/stdio/: regressions.log,
final.log, node.log, verification.json, source-manifest.json,
transcript-manifest.json and per-backend transcripts/CLI byte captures. The
source manifest records 488 inputs. Earlier compile attempts involving read-only
sbt CAS Native outputs are superseded by a clean compile. An initial relay unit
failure exposed the final-append/EOF race and is superseded by the corrected
64-test run. Formatting, Node syntax, workflow YAML and unstaged diff whitespace
checks pass.

CI definitions include JVM-first stdio comparisons; no remote jobs were run.
Windows runtime acceptance remains unavailable. The remaining API/source audit
still includes supporting utilities, command-line support APIs, exact Commons
Math exception/context APIs and uncommon source shapes. Profilers and unsupported
concurrent JS/Wasm execution remain excluded.

Linux aarch64 Native also passes the stdio suite, 64 additional byte-for-byte
comparisons against the retained JVM oracle, two standalone binary CLI runs and
two caller-stream alias runs. All 488 Linux source hashes match the workspace.
Linux evidence is under target/execution-parity/stdio/linux/, including logs,
command script, acceptance/source manifests and transcripts. The disposable
container and volume were removed after retaining evidence; existing containers
were untouched. No commits, pushes or remote CI runs were performed.
