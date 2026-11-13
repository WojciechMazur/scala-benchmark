# Benchmark output channel compatibility

Portable execution distinguishes benchmark user output, harness text and process
bootstrap diagnostics, following JMH 1.37. JVM execution remains in upstream JMH.

| Output source | Destination |
| --- | --- |
| Embedded Scala Console stdout/stderr | Caller ambient Scala Console streams |
| Embedded Java System stdout/stderr | Current System.out / System.err |
| Forked Scala Console and Java System stdout/stderr | Parent System.out / System.err, as separate byte streams |
| Harness progress and handled benchmark stack traces | Formatter text output |
| Process bootstrap and foreign file-descriptor diagnostics | Formatter raw writes |

Benchmark user output bypasses the supplied OutputFormat, `-o` and SILENT
verbosity. SILENT suppresses harness text, while formatter raw writes remain
visible. Output forwarding never closes caller streams. If the caller deliberately
uses the same stream for user output and a formatter, their bytes share that sink.

User console bindings surround the whole executor trial, including state
construction, fixtures, counters and benchmark execution. They are established
outside timed loops. Observer callbacks regain their reporting console scope on
JS/Wasm; Native observers execute on their existing coordinator thread. Ordinary
Scala typechecking, worker assignments and measurement accounting are unchanged.
Printing inside a benchmark remains part of its workload and includes runtime
stream and transport costs; the transport is not intended to make printing cheap.

Native forks append three owned files for stdout, stderr and harness text. Parent
relays tail user files while the worker runs and finish draining after process
termination, including the final EOF/append race. Input handles are closed during
bounded cleanup, including quarantined destinations. Caller-owned streams remain
open. Startup output retains the existing process-pipe path.

JS/Wasm forks use the same three channels through the Node host. Since the parent
coordinator waits synchronously for its worker, it drains channels after the
worker finishes. The host opens read handles before removing the RPC directory;
reads are bounded to 8192 bytes and handles are released even on replay failure.
The default Scala.js console stream buffers lines; during a run only those default
System streams are temporarily replaced with Node byte streams. Caller-supplied
streams retain their behavior. The temporary defaults are restored unless user
code replaces them during the run.

Per-channel bytes and order are preserved, including partial UTF-8 and arbitrary
binary data. There is no total ordering guarantee across stdout, stderr,
framework text and asynchronous formatter callbacks. Direct foreign-code writes
to OS file descriptors remain bootstrap/diagnostic output; they do not use the
Scala/Java user channel. User-output spools have bounded in-memory reads but
consume disk proportional to output. Node bootstrap diagnostics retain the
existing UTF-8 text capture path, which buffers the captured diagnostics; binary
preservation applies to the separate user channels. Existing process/output
deadlines and containment limits apply; a custom synchronous JS callback cannot be forcibly interrupted.
Windows open-file deletion and Native job behavior still need Windows runtime
acceptance; there is no local Windows environment.

The shared `stdio` fixture runs 16 cases per backend: embedded/forked,
NORMAL/SILENT, default/custom formatter and successful/failed benchmark. It emits
constructor and all fixture-level messages, partial UTF-8 and all 256 byte values,
checks result counts and caller ownership, and records four distinct caller
channels. The 64 transcripts compare byte-for-byte with upstream JVM JMH. Two
additional standalone CLI executions per backend check default stdout/stderr
binary preservation with SILENT and `-o`; two further runs check caller-supplied
System streams also aliased by Scala Console. Timing values are never compared.
Node supervisor tests separately cover 20,000-byte channels, multibyte harness
text, crash, missing results, timeout and directory/descriptor cleanup.

Run the JVM oracle first, then portable checks:

```sh
sbt --batch 'sbtPlugin/scripted stdio/jvm; sbtPlugin/scripted stdio/native stdio/js stdio/wasm'
node --test sbt-plugin/src/test/node/*.test.mjs
```

Local acceptance outcomes and retained evidence are recorded in
[the execution ledger](REMAINING_PARITY_WORK.md). CI definitions include these
checks; their presence does not establish remote acceptance.

Primary references: JMH 1.37 [BinaryLinkServer](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/runner/link/BinaryLinkServer.java),
[Runner](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/runner/Runner.java)
and [SilentFormat](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/runner/format/SilentFormat.java).
