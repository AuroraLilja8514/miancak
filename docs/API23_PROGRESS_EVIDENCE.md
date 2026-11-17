# Progress and ordinary main-queue evidence

The androidTest progress sampler records the latest test-thread breadcrumb and
ordinary main-Handler acknowledgement in the debug instrumentation process. It
helps correlate stopped test progress with whether that process continued
servicing ordinary main-queue callbacks. It does not diagnose or fix an ANR.

## Reading the evidence

Every stage is the **latest published breadcrumb**, not necessarily the current
blocking call. Short stages can occur between samples, and partly unmarked code
can execute after a marker. In particular, `SymbolInteractionTest.choose()` has
unmarked operations after a preceding category return. A return marker means
that the marked call returned; it does not establish that its result was good or
that subsequent unmarked code completed.

A fresh acknowledgement proves only that this debug process ran that ordinary
main-Handler callback at the recorded monotonic time. It does not prove input,
windows, UIAutomation or the release app healthy. A missing acknowledgement
cannot distinguish synchronization barriers, queue backlog, a blocked main
thread, a stalled poster/writer, or host/VM starvation. Missing or incomplete
artifacts establish nothing. FirstInstallTest writes its PNG before dumping the
window hierarchy, so a PNG cannot prove that `screenshot()` returned.

The sampler adds allocations/volatile publication on the test thread, a daemon,
ordinary main-queue callbacks, and external-storage writes/flushes. These can
change scheduling, GC, contention and reproduction. No target-thread state/stack
inspection, ART suspension, signals, debuggerd, root, asynchronous Handler or
front-of-queue posting is used. Active ART stack probes can suspend other
threads and fatally abort Android 6 processes; the interaction collector
does not collect process lists or global ART traces. Other bounded local raw
diagnostics are never a public-data sanitization boundary; only the typed public
exporter output is eligible for upload.

Only the sampler's marker/startup/worker failures are best-effort and do not
replace acceptance exceptions. Its `close()` only sets a stop flag.
FirstInstallTest also preserves an in-flight flow exception through its existing
final screenshot/rotation cleanup; a first cleanup exception still fails the
test. Those calls can still block, and their sequential short-circuit behavior
is unchanged. Other tests' existing screenshot/evidence finally blocks retain
their original semantics and can mask an earlier failure. The sampler does not
make those paths nonblocking or generally exception-safe.

On Android 6, even one external-directory resolution on the worker can acquire
a Context lock or call the mount service. Worker placement does not eliminate
shared-lock or storage contention as an observer effect.

## Ownership and cadence

One process guard owns the daemon started before Fn/Symbol or first-install
wake/orientation setup. Nested Symbol publishes to that owner without starting
another worker. Later test objects have their own snapshots even if the guard
refuses their worker. Replacement is refused until the old worker successfully
writes its exit record, closes its sink and has no unresolved posting/callback.
A throwing post retains its reservation because it could already have enqueued.
Late callbacks update only their former owner's acknowledgement fields; they do
not append records. A sink/startup failure may prevent another worker in that
process.

The test thread publishes one immutable latest snapshot: fixed scope/stage,
sequence, elapsedRealtime timestamp, and bounded integer details. The marker
path does not query UI, inspect threads, serialize, or do I/O. A first-failure
latch uses that same in-memory path and publishes a bounded immutable copy of
the latest breadcrumb with fixed phase/kind enums. Sampler `close()`
never joins, interrupts, removes callbacks, writes, closes a sink, or waits.
It is not a quiescence barrier: an attempt racing between the final stop check
and `poster.post` can post after close, and queued callbacks can run later. They
still update only the former owner's acknowledgement fields.

The worker attempts one normal `Handler(Looper.getMainLooper()).post` at nominal
10-second intervals, starting at +10s, while elapsed time is below nominal 840s.
It skips missed slots and permits at most one unacknowledged token, reserved
before posting. Early callbacks and rejected/throwing posts are handled. The
callback only publishes its owner's acknowledgement token/time. The ordinary
queue preserves synchronization barriers.

`Handler.post`, directory operations, writes, flushes and close are **not
independently time bounded**. A worker can remain blocked past 840s; sampler
close cannot rescue it. Time and stop are rechecked after a pre-post write and
before invoking the poster. Existing device-test watchdogs remain independent.

## Artifacts and fields

Only the daemon resolves/creates `context.getExternalFilesDir("test-results")`,
opens, writes, flushes and closes the sampler's JSONL file. There is no fsync or
fallback to private `TestEvidence.directory()`. Both `emulator_tests.sh` and
`first_install_tests.sh` pull external test-results. Allowed filenames are:

- `progress-interactions-main.jsonl`
- `progress-interactions-restart.jsonl`
- `progress-first-install-clean-install.jsonl`
- `progress-first-install-reopen.jsonl`
- `progress-first-install-reboot.jsonl`

An existing file is never truncated. Each sampler artifact is capped at 96
records and 65,536 UTF-8 bytes, including its header and newlines. A full run at
the nominal cadence produces at most 86 records: header, start, 83 cadence
samples and exit. No receiver/editor/user text is included in these records.

`reserved_before_post` is flushed before calling the poster. It establishes a
reservation and snapshot write, not that Handler.post was entered or returned.
A write can itself stall, so even this record is not guaranteed.

| Field | Meaning |
| --- | --- |
| `scenario`, `phase` | Fixed header identities matching the filename. |
| `started_ms`, `at_ms`, `elapsed_ms` | Worker start, sample time, and elapsed time since worker start. |
| `scope`, `stage` | Broad driver/Fn/Symbol/PageEditor/lifecycle/first-install scope and latest published breadcrumb. |
| `seq`, `stage_ms` | Breadcrumb sequence and publication time; intervening short or unmarked operations can be absent. |
| `request`, `ack`, `ack_ms` | Current reservation token, latest acknowledged token, and acknowledgement time. |
| `post_token`, `post_call_ms`, `post_return_ms` | Most recent actual post attempt's token, call time and return time, visible in a later sample or exit. |
| `previous_post` | Latest posting disposition; cancellation before invocation can leave the preceding attempt's token/times in the row. |
| `call`, `category`, `page`, `attempt` | Bounded scenario details described below. |
| `failure` | Schema-2 first-failure observation described below; unavailable is explicit. |

Timestamps use elapsedRealtime milliseconds. Zero means not yet observed for
post/ack fields. Compute actual sample spacing and callback age from recorded
times rather than assuming the nominal cadence. A sample's row timestamp is
captured before reading the latest snapshot and acknowledgement. Concurrent
publication can make `stage_ms`, `ack_ms` or `failure.observed_ms` slightly later than `at_ms`; a small
negative computed age can reflect this ordering rather than a clock failure.
Post dispositions are `none`, `accepted`, `rejected`, `threw_outcome_unknown`,
or `cancelled_before_post`; event
IDs are `start`, `reserved_before_post`, `pending`, `exit_stop`, `exit_deadline`.

For Symbol, `call` counts category invocations, including repeated common calls.
`category` maps common/punctuation/brackets/math/arrows/currency/shapes to 1–7;
zero is unspecified. `page` is zero-based: category selection targets page 0,
page waits record their requested page, and injected touches record the logical
page before the touch. `attempt` is the bounded category search attempt or -1.

For first-install, `call` retains the last screenshot checkpoint ID defined by
`screenshotNumber()` (manual-landscape-split=13). `page` carries the requested
Surface rotation on `ORIENTATION_ENTER` and `ORIENTATION_RETURN`, and the actual
rotation on `ROTATION_RETURN`: 0=natural, 1=90 degrees, 2=180 degrees, 3=270
degrees. Other first-install stages use -1. General unspecified detail fields
use zero or -1 as initialized by their marker call.

### First-failure observation

New sampler files use header `schema: 2`; schema 1 remains readable without
failure metadata. Every schema-2 sample contains `failure` with either exactly
`{"status":"unavailable"}` or the following fixed fields:

| Field | Meaning and bounds |
| --- | --- |
| `status` | `observed`; an observation, not an assertion diagnosis. |
| `phase` | `FLOW` or `CLEANUP`. |
| `kind` | `ASSERTION`, `AUTOMATION`, `TIMEOUT`, `IO`, `INTERRUPTED` or `OTHER`. |
| `observed_ms` | Monotonic time of the first recording attempt that published successfully. |
| `scope`, `stage`, `seq`, `stage_ms` | Immutable latest breadcrumb captured at that boundary. |
| `call`, `category`, `page`, `attempt` | That breadcrumb's existing detail values; bounds remain 0–256, 0–7, -1–31 and -1–31. |

Times and sequence numbers are nonnegative signed-64-bit integers. Kinds use
only `instanceof`: assertion, UIAutomator object-not-found/stale-object,
TimeoutException, IOException, InterruptedException, then OTHER. Messages,
causes, exception class names, thread state, stacks and intent/editor text are
never inspected by the latch.

FirstInstallTest records a caught flow failure before entering final evidence
or teardown. A first cleanup failure is recorded only when no flow failure was
latched. First attempt wins even if its clock/allocation fails; a later cleanup
attempt cannot replace it. No new worker, callback, wait or file operation is
started by the latch. The existing worker serializes the observation in a later
sample if it gets that opportunity. An exit, stalled worker, interrupted pull,
unsupported test path or failed recording attempt can leave it unavailable.
Unavailable does not distinguish no failure from an unobserved failure. Fn and
Symbol do not currently call this failure latch.

The failure's stage is still the latest published breadcrumb: an assertion or
partly unmarked operation may follow a return marker. It does not identify the
failing assertion, prove a cause, or establish app/main-queue health. Later
ordinary breadcrumbs may describe finally/teardown while `failure` retains the
earlier boundary. Once observed, it must remain identical in subsequent samples;
the public exporter rejects a run with a changed or lost latch.

## Host verification

The CI unit step runs the 19 Android-free host experiments after Java 17 setup.
The driver explicitly compiles the exact core from androidTest with temporary
fake clock/poster/sink fixtures; no app sources or Gradle source sets are added.
The same checks can be run locally:

```sh
python3 scripts/test_progress_evidence.py --timeout-seconds 90
python3 scripts/test_script_timeouts.py --timeout-seconds 120
./gradlew --no-daemon test compileDebugAndroidTestJavaWithJavac
```

The experiments cover early/late acknowledgement, pending/rejected/throwing/
blocked posting, blocked open/write/close, nonblocking stop, guard reuse, old
owner isolation, cadence/deadline, UTF-8/record caps and preservation of original
exceptions by the sampler. A finite latch-controlled case also publishes an
acknowledgement from another thread while its worker remains active. Host
checks also cover pre-cleanup failure latching, later replacement attempts,
recorder failure, cleanup exception identity, enum agreement and an actual Java
producer-to-public-exporter roundtrip with failure metadata at full nominal
cadence. Separate write-accounting experiments exercise the hard record/byte
caps. Host
passes do not establish Android runtime health or reproduction; device
acceptance remains a separate check.

The removed one-off `release-anr-diagnostic.yml` depended on an expiring CI
artifact, unsuitable as a durable input for future automated runs. Its deletion
removes that expiring dependency; permanent release collectors and ordinary
main/release acceptance jobs remain independent. The original workflow is
available in immutable baseline commit
`e8eb2fc4897dddd3638f044df3a8e44aef8612fe`.
