# Fork failure containment checkpoint

Local checkpoint: 8 November 2026. JVM measurements continue to use upstream
JMH 1.37. This checkpoint strengthens the Native and Node-hosted JS/Wasm fork
implementations; it does not replace upstream JVM process supervision.

The pre-fix reproduction disables only the worker lifeline, hard-kills the Node
supervisor and observes the infinite-loop benchmark still running. The test
then kills that worker. Evidence is retained in
`target/execution-parity/containment/hardkill-before-fix.log`.

## Implementation

- Native workers start a daemon guard before loading their benchmark suite. The
  coordinator exclusively owns the write end of worker stdin. EOF or a read
  failure causes an immediate C `_Exit(1)`, bypassing potentially blocking user
  shutdown hooks. Worker stdin is reserved for this internal lifeline.
- Node supervisors own a separate pipe to their worker. A host worker thread
  observes its EOF and kills the whole benchmark process. It starts before the
  benchmark module is imported and continues to run when JS/Wasm benchmark work
  blocks the main event loop. It shares no Scala benchmark state and does not
  add benchmark workers or permit `-t 2` on JS/Wasm.
- The synchronous Node coordinator passes its identity to the asynchronous
  supervisor. The supervisor checks that identity at its existing 25 ms polling
  interval and stops its worker if the coordinator disappears, including when
  the coordinator dies before supervisor initialization. The worker pipe also
  covers an uncatchable supervisor kill.
- Native fork cleanup bounds the wait after a forced kill to one second. It
  preserves caller interruption and the original failure, attaching subsequent
  cleanup failures as suppressed exceptions. A surviving process produces a
  fatal containment exception and quarantines the coordinator until that child terminates.
  Infrastructure output relay cleanup remains separate from user lifecycle
  epilogues; failed user setup/work must not trigger extra user teardown.
- A nonzero worker exit is rejected before reading even an already written
  result. Existing parent-side result validation still rejects incomplete,
  malformed or inconsistent payloads. Failed configurations contribute no
  measurements or zero placeholders in these stop-on-error probes. The
  [fork/transport follow-up](FORK_TRANSPORT_PARITY.md) separately documents JMH's
  valid-prefix retention for caught benchmark exceptions with `-foe false`.

These are scheduling-based containment bounds, not hard real-time guarantees.
The guards do not run in the measured benchmark loop.

## Local verification

`containment/native`, `containment/js` and `containment/wasm` each execute a
successful smoke benchmark followed by 13 fault cases under an independent
outer watchdog:

| Fault | Cases per backend |
| --- | ---: |
| Failure at trial/iteration/invocation setup, invocation, and invocation/iteration/trial teardown | 7 |
| Failure during warmup | 1 |
| Non-returning invocation and iteration timeout | 1 |
| External worker kill during non-returning invocation | 1 |
| Coordinator hard-kill during trial setup, invocation and trial teardown | 3 |

The driver verifies non-success exit, termination of observed descendants, and
absence of measurements for the failed configuration. It retains individual
logs, marker files, any result files, outcome records and SHA-256 artifact/source
fingerprints under `target/execution-parity/containment/{native,js,wasm}`.
These are structural failure assertions; timing scores are not compared.
Existing generated-JMH differential tests establish lifecycle failure ordering.

The Node host tests additionally cover expired and invalid progress leases,
SIGTERM, supervisor SIGKILL during synchronous infinite work, coordinator
SIGKILL, startup after owner loss, missing results, and a nonzero exit after
writing a result. The JVM runtime suite includes a real subprocess interruption
probe with a simultaneous cleanup failure, pipe EOF detection, and an isolated
fault-injection process that refuses forced termination to check bounded cleanup
and quarantine without leaking an actual unkillable process.

Run locally:

```sh
node --test sbt-plugin/src/test/node/*.test.mjs
sbt --batch 'runtimeJVM/testOnly *Test; sbtPlugin/scripted containment/* execution/*'
```

Use the default library Scala version for scripted publication; run `reload`
after any `++` command before invoking these scripted suites.

## Remaining acceptance work

- The framework-owned coordinator/supervisor/worker chain is covered. Arbitrary
  subprocess trees created by benchmark code are not yet contained by OS process
  groups or Windows jobs. Stopped processes that cannot schedule their guard,
  and combinations of simultaneous supervisor/worker failures, need more tests.
- Hard-killing the coordinator can leave temporary transport files. The worker
  termination guarantee does not imply transactional disk cleanup.
- Verification here is local macOS only. Linux and Windows still need actual
  linked execution, including Node's extra pipe handles and Windows parent
  identity/PID reuse behavior. The Node coordinator identity probe uses PID
  liveness; a Windows process handle would provide a stronger identity contract.
- Larger Native grouped/shared-state failure and counter matrices remain open,
  including failure during shared invocation fixtures and timed-mode teardown.
- Warmup-fork failure, later measurement-fork failure, serialization interruption,
  output-pipe backpressure, repeated cancellation and public Runner recovery
  need an expanded transport/failure interaction matrix.
- Uncooperative embedded JS/Wasm remains uncontainable on its single benchmark
  event loop. Fork isolation is required for that case.

No remote verification, commits or pushes were performed for this checkpoint.
