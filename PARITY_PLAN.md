# JMH 1.37 parity roadmap

Target source and behavioral parity wherever the host can implement JMH semantics.
JVM measurement stays in upstream JMH. JS/Wasm remains single threaded when
ordinary concurrent Scala state cannot be shared. Profiling is deferred.
`WORK_SUMMARY.md` records implementation status, not planned capabilities.

## Dependency order and completion contract

1. **Compatibility contract and eligibility:** `scala.benchmark.Platform` and
   `@Platforms`; complete generated discovery; inherited intersections; common
   exact/regex selection; JVM preflight and harness filtering; shared fixtures.
2. **Runtime and result models:** explicit scopes, group/member identities,
   deterministic worker assignments, injected metadata, secondary metrics;
   update generated harness ABI and both fork protocols together.
3. **Configuration and infrastructure:** `-opi`, `-si`, `-v`, `-o`, `-lrf`,
   `-wm`, `-wmb`, `-tg`; upstream parsing/defaults/precedence; bulk and individual
   warmup; real host GC; BenchmarkParams, IterationParams and Control injection.
4. **Execution semantics:** Native grouped concurrency and all state scopes;
   reject JS/Wasm multiworker configurations; OPERATIONS/EVENTS auxiliary
   counters; batching/normalization/sampling/fixture/synchronization audit;
   remove artificial source restrictions, retain genuine backend limitations.
5. **Public APIs:** non-JVM Runner/exceptions, Options/builders, CommandLineOptions,
   TimeValue and supporting types; public result/statistics/iteration/fork APIs.
   Definitions live in interface, execution in runtime, using generated inventories.
6. **Integration and documentation:** compare deterministic shared fixtures with
   upstream JMH; six report formats and secondary metrics; compiler matrix;
   actual JVM/Native/JS/Wasm scripted checks and Native Linux/macOS/Windows CI;
   examples and an accurate capability matrix.

A capability is complete only when compilation, discovery, selection, execution,
transport, public results, reports and documentation agree. Compare configuration,
lifecycle traces, validation, state identities and result structure, never timing
values for equality. Include failure, cancellation, timeout and fork coverage.

## Platform eligibility contract

No annotation allows JVM, Native, JS and Wasm. Class and method restrictions,
including inherited restrictions on overridden methods, intersect. An empty
annotation or effective intersection is a compilation error. Optional reasons
appear in diagnostics. All source bodies still typecheck on every backend.

Inventory retains incompatible benchmarks. Apply eligibility before backend
harness validation/generation. JS versus Wasm is resolved at launch from linker
metadata, never baked into their shared artifact. JVM delegates supported
measurements to upstream JMH after selection preflight.

Unfiltered and regex selection skip incompatible entries and report name, current
platform, allowed platforms and reasons. Resolve literal canonical inventory
names before interpreting other includes as regex. Exact incompatible requests
fail before any selected benchmark runs; match count never determines intent.
Exclusions suppress both execution and incompatibility diagnostics. Listing shows
restrictions without execution or failure. An all-incompatible aggregate succeeds
with skips and no fabricated results; no matches remains an error. Complete
selection before forks or state/fixture initialization. CLI and programmatic
entry points share this policy. Test incremental annotation edits, renames and
removals, inheritance, JS/Wasm distinction, unsupported excluded harness features
and ordinary typechecking errors.

## Exclusions

Profilers and `-prof`/`-lprof`; conditional removal of source bodies; Scala 2;
browser hosting; changes to Scala.js concurrency; emulation of unsupported JVM
internals or compiler controls. An eligible benchmark requesting an unsupported
capability still fails clearly. JVM arguments, Native build settings and Node
options remain separate.

## Execution semantics priority

Measurement soundness is the priority for the next implementation work. The
[execution semantics plan](EXECUTION_PARITY_PLAN.md) defines the ordered audit,
initial reproductions, independent JMH reference checks, acceptance gates and
platform evidence needed before declaring execution parity. The current
aggregation/counter checkpoint and remaining limits are recorded in
[AGGREGATION_PARITY.md](AGGREGATION_PARITY.md). The owned-fork hard-kill and
cancellation checkpoint, including the remaining process-tree/fault matrix, is
recorded in [FORK_CONTAINMENT.md](FORK_CONTAINMENT.md). Grouped failures,
partial-iteration rejection and continue-on-error/public Runner recovery are
recorded in [GROUP_FAILURE_PARITY.md](GROUP_FAILURE_PARITY.md). Forked failure
classification, valid-prefix retention, serialization faults and output
backpressure are recorded in [FORK_TRANSPORT_PARITY.md](FORK_TRANSPORT_PARITY.md).

## Remaining acceptance work

The implementations and local evidence are recorded in `WORK_SUMMARY.md`.
Keep the following audits open until differential tests establish parity:

- Annotation inheritance and conflicts across class/method/group declarations;
  duplicate parameter names/default precedence and uncommon source shapes.
- Generic bounds beyond erased concrete state bounds; nested/static state and
  benchmark definitions; grouped overloaded method validation.
- Lifecycle ordering, synchronization, sampling and cancellation under failures,
  including Native platform-specific behavior.
- Complete public supporting/extension APIs, less common user-defined aggregation contracts. Custom OutputFormat integration
  and callback-added report metrics are covered by the checkpoint below.
- Native Linux and Windows outcomes in CI; local macOS runs are separate evidence.

Compiler compatibility jobs run compiler tests for every supported Scala version.
Scripted checks must publish the default oldest-compiler library artifacts before
running fixtures; a prior `++` must be reset with `reload` to avoid stale artifacts.


The sequential execution ledger is [REMAINING_PARITY_WORK.md](REMAINING_PARITY_WORK.md).
The implementation/acceptance distinction and remaining public API gaps are
recorded in [CAPABILITY_MATRIX.md](CAPABILITY_MATRIX.md). Windows execution cannot
be locally accepted without a Windows environment; cross-compilation alone is
not sufficient.


The custom OutputFormat implementation checkpoint is documented in
[OUTPUT_FORMAT_PARITY_PLAN.md](OUTPUT_FORMAT_PARITY_PLAN.md). Further result and
source API compatibility work is tracked in
[API_SOURCE_COMPATIBILITY.md](API_SOURCE_COMPATIBILITY.md); current verification
belongs in WORK_SUMMARY.md and REMAINING_PARITY_WORK.md.

The public result-format factory and enum source-call checkpoint is recorded in
[RESULT_FORMAT_FACTORY_PARITY.md](RESULT_FORMAT_FACTORY_PARITY.md).

The standard output-format factory checkpoint is recorded in
[OUTPUT_FACTORY_PARITY.md](OUTPUT_FACTORY_PARITY.md).


Benchmark user output transport is documented in [STDIO_PARITY.md](STDIO_PARITY.md).
Keep foreign file-descriptor output, caller-defined synchronous JS streams and
Windows file-handle/runtime behavior distinct from the verified Scala/Java user
channels. Future execution changes must retain per-channel byte preservation,
caller ownership, failure context and cleanup outside measured phase accounting.
