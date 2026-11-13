# Fork failures, transport interruption and output backpressure

Local checkpoint: 8 November 2026, JMH 1.37. JVM measurements remain in upstream
JMH. This checkpoint implements and verifies Native/JS/Wasm fork-failure
classification, result retention and bounded output handling.

## Retention follows the executable JMH oracle

JMH distinguishes a caught benchmark exception from a crashed or invalid fork.
The distinction matters when `-foe false` is used:

| Event | Continue-on-error behavior |
| --- | --- |
| Exception in a warmup fork | Diagnose it, exclude warmup observations, run subsequent forks |
| Exception in a measurement fork | Retain completed measurement iterations, exclude the failed iteration, run subsequent forks |
| Exception in final trial teardown | Retain preceding iterations; the last iteration's failed epilogue does not produce a result |
| Crashed fork or corrupt protocol | Discard this configuration's observations, including earlier successful forks; later configurations may run |
| Any benchmark failure with `-foe true` | Abort; no partial result for the failed configuration |
| Blocked/failed output channel | Abort and quarantine the output channel; do not launch further work or retry console reporting |

For example, two requested measurement forks with two iterations each can yield
raw populations `[2, 1]` after the second fork fails trial teardown under
continue-on-error. If that fork crashes instead, the configuration produces no
result. These outcomes come from running the shared fixture against JMH 1.37,
not from assuming that all failures have the same retention policy.

Portable fork workers now report explicit benchmark failures separately from
process/protocol failures. Only validated completed observations are retained.
Failure records cannot make an incomplete ordinary payload valid: retained
prefixes require an explicit, well-formed failure record, have fewer than the
requested iteration count, and undergo the normal raw-observation validation.
Failure causes remain diagnostic data; they are never zero-valued measurements.

The public Runner carries the actual fork and iteration containers. A failed
fork has no completed-trial metadata (`getMetadata() == null`), matching JMH;
valid-prefix transport cannot fabricate it. Requested fork/iteration parameters
remain requested values, matching JMH. Portable CLI
runs emit `# FAILED fork` diagnostics and `# INCOMPLETE` counts when the retained
measurement population is smaller. Callers requiring stop-on-error should use
`-foe true` or `shouldFailOnError(true)`.

This checkpoint tests forked retention. It does not change embedded (`-f 0`)
late-failure retention; that broader compatibility audit remains separate.

## Transport and output containment

Native relays worker output in bounded character chunks, preserving UTF-8 and
long lines without accumulating an entire line. Both worker streams go through
the relay so inherited stderr cannot bypass output supervision. Relay completion
uses bounded joins. A surviving relay is quarantined rather than synchronously
closing a stream that may itself be blocked. Known primary failures remain the
cause when relay cleanup also fails, with cleanup diagnostics retained separately.

Node captures worker stdout/stderr in files. Normal console replay runs in a
separate, killable process with the configured timeout, using bounded byte
buffers. It cannot trap the synchronous Scala coordinator on a full inherited
pipe. Redirected/silent Scala console output retains its configured routing.
The new relay resource is packaged with JS/Wasm launchers and retained by the
optimized-artifact inspection helper. This bounds fork-output replay; general
caller-owned console/report sinks are outside that guarantee.

A failed output channel prevents further measurements and final console tables,
including when earlier configurations completed successfully. Those earlier
valid results remain in the result file. CLI termination bypasses potentially
blocked stream flushing; programmatic callers receive a fatal error and the
process remains quarantined for further measurements.

Native and Node require a regular result file before reading it. A replaced FIFO
is rejected without opening it. Truncated records, incomplete observation arrays,
trailing data and nonzero worker exits cannot contribute scores. Normal managed
faults clean up their transport files.

## Local verification

- **24 sequence/report cases per backend**, JVM, Native, JS and Wasm: healthy
  isolation; setup/work/teardown exceptions; process crashes; warmup-fork and
  later-measurement-fork failures; both error policies; all six project report
  formats. Portable normalized outcomes match the independently run JVM oracle.
- **Public Runner probe on all four backends** checks actual retained fork
  containers and requested fork parameters after a later fork fails.
- **18 linked transport probes** across Native, JS and Wasm cover truncated,
  incomplete and trailing records, non-regular FIFO results, interrupted writing
  and blocked output. The output probe first completes an unrelated benchmark,
  then stops consuming stdout: the valid earlier result survives, the blocked
  configuration contributes nothing, and recovery work never starts.
- Native's actual serializer writes a payload larger than a pipe buffer. The
  driver reads 64 bytes, stops reading and kills the worker while serialization
  is blocked. JS/Wasm test preloads kill the worker during a partial result write.
- JVM runtime tests reject every byte prefix of a binary observation record and
  validate explicit failure prefixes. A separate-process output probe verifies
  bounded quarantine and preservation of a primary exception when cleanup fails.
- **14 Node host tests** include blocked stdout and stderr, preserving a known
  benchmark-failure diagnostic, partial-write death, timeout, signals, missing
  results and hard-killed owners.

The upstream CLI has no HTML format. The HTML retention case uses the same JMH
run with text output as its selection/exit oracle; portable HTML is checked for
the retained benchmark identities. The existing JVM HTML adapter is checked by
the report regression suite. No runtime timing scores are compared for equality.

Evidence is under `target/execution-parity/fork-sequence/{jvm,native,js,wasm}`:
normalized outcomes, individual logs/reports, fork ledgers, transport outcomes,
fixture/build hashes and the retained serialization prefix. The pinned upstream
`Runner` source is under `reference`. Fault injection is confined to test-only
fixtures, shutdown hooks and Node preloads, outside production measured loops.

```sh
node --test sbt-plugin/src/test/node/*.test.mjs
sbt --batch 'runtimeJVM/testOnly *Test; compilerPlugin/testOnly *CompilerTest *StateGraphParityTest; sbtPlugin/scripted fork-sequence/jvm; sbtPlugin/scripted fork-sequence/js fork-sequence/native fork-sequence/wasm'
sbt --batch 'sbtPlugin/scripted containment/* group-failures/* aggregation/* reports/* execution/*'
```

Generate the JVM oracle before portable sequence suites. Scripted publication
requires default library settings; run `reload` after any `++` command.

## Remaining limits

Verification is local macOS only. FIFO fault probes require a POSIX host; Windows
needs equivalent named-pipe checks and actual linked execution. This establishes
bounded responses to the controlled pipe/process faults above, not hard real-time
guarantees or interruption of arbitrary kernel I/O.

Bulk warmup-action failure, embedded late-failure retention, arbitrary
benchmark-created subprocess trees, hard-kill transport-directory recovery and
larger cancellation/output/file-system interaction matrices remain open.
Arbitrary blocking Scala output callbacks in embedded JS/Wasm cannot be stopped
by its single event loop. Profilers remain excluded.

No remote verification, commits or pushes were performed.
