# Grouped failure and recovery checkpoint

Local checkpoint: 8 November 2026, JMH 1.37. This extends the
[fork-containment checkpoint](FORK_CONTAINMENT.md) with generated grouped
benchmarks, shared invocation-fixture failures, partial-iteration rejection and
public Runner recovery. JVM measurement remains in upstream JMH.

## Reproduced mismatch and fix

Native previously ran a later successful benchmark under `-foe false`, saved its
valid result, then threw an overall exception for the earlier failed benchmark.
The same fixture under upstream JMH returned normally with only the successful
benchmark. Evidence from both runs is retained under
`target/execution-parity/group-failures/before-fix`.

The shared portable runner now follows JMH's continue-on-error policy:

| Outcome | Result |
| --- | --- |
| Recoverable failure, `-foe false` | Diagnose the failure and continue; return completed valid observations |
| All selected configurations fail, `-foe false` | Return an empty result collection; do not fabricate a measurement |
| Failure, `-foe true` | Stop and propagate the error |
| Caller interruption | Stop, preserve the interrupt flag, do not execute later configurations |
| Surviving embedded benchmark workers | Stop with a fatal containment error and quarantine the process |

A benchmark worker throwing `InterruptedException` is distinct from cancellation
of the calling thread. Tests verify that the former remains recoverable with
continue-on-error, while actual caller interruption always stops execution.
CLI and public Runner use this same configuration loop. The later
[fork/transport checkpoint](FORK_TRANSPORT_PARITY.md) additionally establishes
JMH's retention of valid prefixes for caught forked benchmark exceptions. The
continue-on-error grouped cases here fail before their first completed iteration;
the second-iteration rejection cases request explicit stop-on-error. Callers that require a
nonzero exit on any benchmark failure should specify `-foe true` (or
`shouldFailOnError(true)`); the default continuation policy can return normally
with an empty result set, as in JMH.

## Independent reference and linked checks

The shared `GroupFailureBenchmark.scala` fixture runs through upstream JMH's
generated JVM harness and through the Native compiler-generated harness. Each
backend has 26 controlled CLI cases:

- Eight failure boundaries: shared Trial/Iteration/Invocation setup, benchmark
  work, shared Invocation/Iteration/Trial teardown, and auxiliary counter capture.
  Each runs as a two-worker SingleShotTime group and as two synchronized
  Throughput groups with warmup (16 cases).
- Cooperative peer release in AverageTime and SampleTime, each with iteration
  synchronization enabled and disabled (4 cases).
- Failure in the second measurement iteration, after a completed first iteration,
  in work, iteration teardown and counter capture (4 cases).
- Continue-on-error followed by a successful configuration, for work and counter
  failures (2 cases).

The driver uses bounded outer watchdogs, tracks child processes, checks the
injected primary cause and one failing shared fixture/member, and verifies the
peer's required entry/release events. It compares semantic observations rather
than imposing a total ordering on concurrently scheduled workers. Failed group
configurations never appear in report measurements, including when an earlier
iteration completed successfully. With continuation enabled, only the subsequent
recovery benchmark appears in the report.

Native adds two cases with a non-returning peer: an isolated fork must terminate,
and an embedded run must fail fatally before its selected recovery benchmark
executes, even with fail-on-error disabled. These do not claim safe interruption
of arbitrary benchmark threads; the embedded test process exits after checking
the containment error.

Both JVM and Native also execute the same grouped public Runner recovery probe.
A separate portable fixture checks failed-only empty results, successful recovery
and explicit stop-on-error on JVM, Native, JS and Wasm. JS/Wasm group execution
remains unsupported; those backends verify ordinary single-worker failure
recovery through the shared runner.

The compiler differential driver additionally compares failures in shared
Benchmark- and Group-scoped invocation setup/teardown with independently generated
JMH Java harnesses. The existing shared-fixture matrix now has 14 cases, covering
construction and all fixture levels, and asserts that peers do not retry a
failed shared fixture.

## Evidence and local commands

`target/execution-parity/group-failures/{jvm,native}` retains per-case logs and
reports, normalized event/outcome records, smoke results, SHA-256 provenance,
generated JMH sources and the Native executable. The internal fork-host fixture
lives in `sbt-plugin/src/test/fixtures/containment` and is included only by the
non-JVM containment builds; the general JVM fixture set uses upstream JMH without
a dependency on the portable runtime. Shared graph evidence remains
under `compiler-plugin/target/execution-parity/state-graphs`.

```sh
sbt --batch 'runtimeJVM/testOnly *Test; compilerPlugin/testOnly *CompilerTest *StateGraphParityTest; sbtPlugin/scripted group-failures/* containment/*'
sbt --batch 'sbtPlugin/scripted platform/* execution/*'
```

Scripted checks require default library publication settings; run `reload` after
`++` before invoking them. Verification is local macOS only. No commits, pushes
or remote checks were performed.

## Remaining acceptance work

- Grouped counter reset/aggregation combined with other simultaneous fixture
  failures, larger dependency graphs and broader forced-scheduling repetitions.
- Exhaustive timed-mode shared Benchmark-state invocation interactions, inherited
  fixture failures and all batch/OPI combinations under cancellation.
- Later-fork and warmup-fork failure, interrupted serialization, output
  backpressure and repeated process cancellation.
- Arbitrary benchmark-created subprocess trees, hard-kill transport-file cleanup
  and actual Linux/Windows acceptance, as described in the containment checkpoint.

These checks establish selected soundness properties; they do not complete all
execution, counter, source or public API parity gates.
