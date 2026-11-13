# JMH 1.37 capability and acceptance matrix

This matrix distinguishes implementation from evidence. JVM measurement always
uses upstream JMH 1.37. The portable runtime is not certified for the entire JMH
API. Timing values are never compared for equality across runtimes.

| Capability | JVM | Native | JS / Wasm | Acceptance evidence |
| --- | --- | --- | --- | --- |
| Platform restrictions, inherited intersections, exact/regex selection | Eligibility preflight, upstream execution | Implemented | Implemented, distinct launch identities | Shared platform fixtures and compiler tests |
| Four modes, batching, OPI and operation accounting | Upstream | Implemented | Implemented for one worker | Execution and aggregation reference suites |
| Benchmark, Group and Thread state; dependent fixtures | Upstream | Shared concurrent states | Single worker; multiple workers rejected | State-graph identity and lifecycle comparisons |
| Groups and member results | Upstream | Concurrent members | One active worker only | Grouped execution and controlled-failure fixtures |
| OPERATIONS / EVENTS auxiliary counters | Upstream | Implemented | Implemented | Counter reset, capture, mode and report checks |
| Bulk/individual warmup and extra warmup selection | Upstream | Implemented | Implemented | 36 warmup failure/continuation cases per backend |
| Early and late embedded failures | Upstream | Early continuation; clear fatal late metadata failure | Same portable policy | 16 embedded reference cases per backend; late JMH failure itself is fatal |
| Fork failures and completed prefixes | Upstream | Valid completed observations only | Valid completed observations only | 24 sequence/report cases per backend; separate transport faults |
| Process trees, cancellation, blocked standard output | Host behavior | POSIX groups; Windows jobs implemented | POSIX groups; Windows taskkill best effort | 15 additional linked probes per portable backend on macOS; Windows execution unavailable |
| Parameters, Control and infrastructure injection | Upstream | Implemented | Implemented | Shared infrastructure and configuration fixtures |
| Runner/options and result extension APIs | Upstream | Implemented subset | Implemented subset | API fixtures exercise custom aggregation, collection aliasing and raw observations |
| Result extension and collection helpers | Upstream | Implemented checkpoint | Implemented checkpoint | Shared result-extensions fixture; protected APIs, text/raw/task results, singleton statistics and collection utilities |
| Standard output-format factory | Upstream | Implemented, adapted JVM diagnostics/summary | Implemented, adapted JVM diagnostics/summary | Shared metadata/iteration/extended-result transcripts, stream ownership and Runner fixtures |
| Benchmark user output channels | Upstream | Separate stdout/stderr, live file relays | Separate stdout/stderr, post-fork drains | Shared 64-channel JVM comparisons and binary CLI checks; see STDIO_PARITY.md |
| Public result-format factory | Upstream | Implemented, adapted presentation | Implemented, adapted presentation | Standalone public result fixtures; stream/file ownership, metadata and raw observations |
| Statistical comparisons, confidence errors, histograms | Upstream | Implemented JMH algorithms | Same portable implementation | Shared deterministic statistics fixture; numerical reference tests |
| Console and six report formats, secondary metrics | Upstream/adapted reports | Implemented | Implemented | Shared report fixtures and fork-sequence report checks |
| Object-scoped states and overloads in separate groups | Upstream generator | Generated direct calls | Generated direct calls | Shared API fixture and compiler execution test |
| Compiler controls | Upstream | Supported inline/noinline equivalents | Supported inline/noinline equivalents | Optimized artifact checks; unsupported controls rejected |
| Collection requests | Upstream | Runtime collection request | Node collection, requires `--expose-gc` | Platform checks; integrity suites also request collection |
| Internal iteration observation | Executor test harness | Implemented, disabled by default | Implemented, disabled by default | Shared lifecycle/failure/counter checks and public callback integration |
| Public custom OutputFormat Runner integration | Upstream, eligibility preflight in Scala facade | Implemented | Implemented | Genuine fork events, warmup/failure traces, public result identity and callback-added report metrics; see OUTPUT_FORMAT_PARITY_PLAN.md |
| Profilers and profiler APIs | Upstream | Excluded | Excluded | Outside this roadmap |

## Host and compiler acceptance

macOS arm64 is the primary local verification host. Linux aarch64 runs in a
disposable local Docker container with an isolated source copy. Its strict C
build exposed missing POSIX feature declarations; the source now explicitly
requests POSIX.1-2008 on Linux. Windows-target C compilation validates the Native
job API declarations, but does not establish Windows execution acceptance.
The user confirmed that no local Windows environment is available. CI workflow
changes are prepared locally; no remote jobs, commits or pushes are performed.

Final commands and outcomes for this work are recorded in
[the execution ledger](REMAINING_PARITY_WORK.md). Existing evidence directories
under `target/execution-parity` are local build artifacts, not committed fixtures.
A CI job's presence is not a passing acceptance result.

## Remaining limits

- Less common supporting and extension APIs remain outside the implemented API
  checkpoint. Standard output/result factories are documented in the respective
  factory checkpoints. Custom OutputFormat Runner
  callbacks use genuine executor events; callback failures deliberately remain
  fatal even where JMH 1.37 can swallow them. See API_SOURCE_COMPATIBILITY.md.
- Portable statistics reject invalid inputs through IllegalArgumentException;
  the complete Commons Math exception/context API is not supplied.
- Arbitrary inner benchmark/state classes requiring an enclosing instance,
  object-scoped benchmark discovery and more complex generic bounds remain open.
- Annotation conflicts across grouped members and unusual inheritance/default
  combinations need broader differential coverage; the shared configuration
  fixture covers explicit CLI/builder overrides, parent option precedence and
  partial class/method annotations across a superclass chain. Group annotation
  collision rejection is checked against the upstream generator.
- POSIX descendants can deliberately escape process groups. Custom synchronous
  Scala output callbacks cannot be forcibly interrupted on the JS event loop.
  Windows Node cleanup is best effort; Native job behavior awaits Windows runs.
- Upstream JMH does not expand `@Param` for additional `-wmb` benchmarks. The
  warmup-failure oracle uses separate classes to exercise lifecycle policy.
