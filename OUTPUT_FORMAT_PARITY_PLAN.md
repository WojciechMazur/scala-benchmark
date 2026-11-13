# Custom OutputFormat parity checkpoint

The public interface, Runner constructor/service bridge, executor events and
Native/JS/Wasm fork delivery are implemented. JVM measurements still execute in
upstream JMH 1.37. Final acceptance and source hashes are recorded in
REMAINING_PARITY_WORK.md; intermediate failed runs are not acceptance evidence.

## Execution and delivery contract

Embedded callbacks execute synchronously. The first iteration callback precedes
construction and trial setup; the final result follows trial teardown and final
counter capture. Warmup callbacks carry real observations but do not enter
measurement populations. Native workers wait outside timed barriers while user
callbacks run. Cancellation restores interruption and reaps workers, including
when an observer restores the interrupt flag before throwing.

Fork workers publish sequenced, data-only records in their owned RPC directory.
Records identify the main or bulk warmup action and preserve iteration starts,
results, flush and close. Publication uses a pending file followed by rename.
Parents validate order, worker counts, operation/batch accounting and raw scores
before invoking callbacks. Valid published prefixes survive worker failure;
normal completion requires a closed stream. Files are removed on normal exit,
cancellation and owner loss. Callbacks and benchmark state are never serialized.

JMH fork callback delivery is asynchronous and can lag behind worker fixtures.
Portable parents replay genuine events after reaping the worker. No worker/parent
acknowledgments are added; callback delivery cannot extend a measurement watchdog.
Native group overrides are resolved before constructing callback metadata or
validating warmup observations.

## Public results and reports

Both `org.openjdk.jmh.runner.Runner` and `scala.benchmark.Runner` accept custom
formats. Listing uses the custom output without starting execution. Empty listing
succeeds; a run with no matches preserves the normal selection error.

Iteration callback objects are retained in public benchmark results, and the
collection delivered to `endRun` is returned to the caller. Callback-added metrics
and edits to that collection are reflected in report files. Report views use
public scores, units, confidence intervals, percentiles and observations; they do
not synthesize worker timings. Custom formats own console result presentation.
The report bridge accepts results from the current run; it is not a general
formatter for arbitrary manually supplied benchmark identities.

## Deliberate failure policy

A custom callback failure is a fatal infrastructure error even with fail-on-error
disabled. JMH 1.37 can swallow iteration callback failures as benchmark/link
failures. The shared oracle records this difference rather than copying that
behavior. Existing late embedded benchmark failure diagnostics remain explicit;
JMH itself can fail while looking up missing completed-trial metadata.

Captured JS/Wasm worker diagnostics are replayed even when the fork fails before
returning a result. This preserves the original warmup/benchmark cause.

## Acceptance fixtures

`output-format/{jvm,native,js,wasm}` compare six normalized callback/lifecycle
traces and exercise all modes, counters, individual/bulk warmup, warmup forks,
failures, callback errors, listing, result mutation and worker crashes. JVM and
Native also check grouped results and overridden group distributions with bulk
warmup. Native checks eight public Runner cancellation boundaries. Callback-added
metrics are checked in all six portable reports for every mode; upstream JMH has
five native public result-format enum values.

`ForkEventsTest` checks every completed prefix, malformed sequences, raw-data
corruption and missing closure. Node supervisor checks cover success, crashes,
missing results, timeout, sequence gaps, retained diagnostics and RPC cleanup.
Broader containment, sequence, group, report and runner-failure suites remain
required regression checks. Actual Windows execution is unavailable locally.
