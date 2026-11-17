# Public device-test evidence boundary

Device drivers retain local raw diagnostics under `test-results/` and a bounded
console capture under `private-test-results/`. Neither tree is a public artifact
input. App-focused filtering, a disposable guest name, truncation, tar extraction
filters and synthetic test inputs are not personal-data sanitizers.

Every maintained device workflow runs its original driver through
`scripts/public_test_evidence.py run`. The command, environment, assertions and
existing watchdog budgets remain those of the driver. The wrapper drains both
output streams, retains at most 8 MiB locally, and prints only fixed status and
progress tokens. A nonzero original exit remains nonzero, including when later
local report I/O fails. Timeout/interruption can leave detailed evidence absent;
that absence does not establish any cause or recovery.

A separate `if: always()` staging step reconstructs public JSON from a fixed
allowlist. Upload is conditional on that step succeeding and names only:

- `public-test-results/summary.json`
- `public-test-results/heartbeat.jsonl`

The destination must be new. Symlinks in source components, non-regular or
hard-linked inputs, path traversal, oversized files and unsafe output paths are
refused. Arbitrary files and archives are never recursively copied or unpacked.
A failed staging step blocks upload; it does not turn the original device step
into success. Export success means serialization succeeded, not APK acceptance.

Public output contains only fixed phase identifiers/outcomes, bounded counts and
timings, numeric native/word-pack/footprint metrics, the harness Git revision and
SHA-256 hashes of explicit APK inputs,
fixed diagnostic availability statuses, coarse raw-log marker booleans and
strictly checked heartbeat protocol fields. Raw marker booleans do not identify
an app, prove an ANR, establish a cause or establish health. They mean only
"seen in the bounded files read"; `raw_marker_sources_read=0` means no source
was available, and false does not establish the absence of a failure. Raw log text, stack
traces, process/package listings, exception messages, receiver text, preference
values, per-file learning paths/digests, databases, backups, XML, images, APK
copies and opaque archives are excluded. Learning preservation still compares
its original complete local maps; only counts/byte totals may be summarized.

Heartbeat stages retain their latest-breadcrumb interpretation, publication
ordering limits and observer effects. Public projection does not make the
underlying instrumentation stack-probe-free or its file/post operations bounded.
Summary schema 2 adds the fixed `failure_evidence` map and `reboot_evidence`
object below. The same two reconstructed files and size caps apply; it does not
permit raw additional attachments. Legacy schema-1 heartbeat inputs remain
readable, with their failure metadata explicitly unavailable.

`failure_evidence` has the same five allowlisted run filenames as `heartbeat`.
Each value is the observed immutable schema-2 sampler failure, or exactly
`{"status":"unavailable"}`. A missing/invalid heartbeat, absent recording call,
failed recorder, stopped writer or legacy input can all produce unavailable.
The retained phase/kind and latest breadcrumb narrow the observed boundary;
they do not identify an assertion message, cause or app health. See
[sampler fields and limits](API23_PROGRESS_EVIDENCE.md#first-failure-observation).

## Independent reboot observations

`reboot_evidence` is either exactly `{"schema":1,"status":"unavailable"}` or a
strict schema-1 projection of observations from the reboot driver's existing
operations. It includes `status: available` and these fixed fields:

| Field | Meaning and bound |
| --- | --- |
| `stage` | Latest published `INITIAL_CHECKPOINT`, `READ_BOOT_ID`, `REBOOT_REQUEST`, `POLL_BOOT_ID`, `POLL_COMPLETION`, `WAIT_BOOT`, `CYCLE_CHECKPOINT` or `COMPLETE` breadcrumb. |
| `cycle` | Current cycle, 1–5; 0 before the first cycle. |
| `elapsed_ms` | Host monotonic elapsed time since the observation object was started, 0–86,400,000 ms. |
| `cycle_elapsed_ms` | Elapsed time from the existing cycle start, same bound; null before that start. |
| `poll_attempt` | Number of the latest poll in this cycle, 0–3601. |
| `poll_status` | `UNAVAILABLE`, `OK`, `TIMEOUT` or `ADB_ERROR` for the latest attempted poll. |
| `boot_id_changed` | Existing valid boot-ID comparison as a Boolean; null when not obtained or the latest ID was invalid. IDs are never projected. |
| `completion` | `COMPLETE` for 1, `NOT_COMPLETE` for 0, `UNAVAILABLE` for empty/not obtained, or `INVALID` for any other completion output. |
| `failure` | First observed failure snapshot of these same fields plus `status: observed` and a fixed `code`, or exactly `{"status":"unavailable"}`. |

Failure codes are `INVALID_BOOT_ID`, `BOOT_DEADLINE`, `ADB_TIMEOUT`, `ADB_ERROR`,
`HOST_IO` and `OTHER`. They classify an existing harness boundary/result, not an
ANR or guest root cause. The first attempt cannot be replaced by later
collector/report failures. Report I/O during an already failing path preserves
the original exception object; report I/O failing alone still fails the driver.
Existing reboot checks, commands, polling, deadlines and cycle counts are
unchanged. Observations add no guest probes.

Every new cycle/poll resets its availability fields. A completion-query failure
can leave a valid comparison from that same attempted poll with unavailable
completion; neither then establishes that boot completed. `OK` means both
existing ADB reads returned, including empty/invalid data. `WAIT_BOOT` can be the
latest breadcrumb after those operations or their handled transport error; it
is not necessarily the current blocking call. The immutable failure snapshot
and the latest checkpoint can have different elapsed times/stages.

The observation object starts after the existing target and emulator-version
checks. Preflight rejection, a missing final report or an abrupt stop may leave
only an older checkpoint or no metadata. A failed recorder is best-effort;
unavailable/partial evidence must not assert health, recovery or a cause. Raw
diagnostics remain local and cannot be reconstructed from public summaries.
Safe summaries preserve driver failure but cannot replace detailed visual
acceptance evidence or establish an ANR cause.

## Other evidence and target checks

N3 remains opt-in for measurement execution. Its public policy is narrower than
its local capture format: only four fixed reported profile outcomes, viewport
counts and a restoration Boolean may be projected. Screenshots, geometry, text,
hierarchies and failure reasons remain local, with public detailed evidence
marked unavailable. A prepared-state claim alone does not authorize image/text
publication. There is no raw-upload override.

Legacy shell drivers verify emulator serial, QEMU/API, exact dedicated AVD name
and absence of a pre-existing MIANCAK install before installing, mutating or
starting collection. Empty-install queries require host success, an explicit
complete remote zero-status marker and no other output. Empty stdout alone is
unavailable, including on legacy shell-v1 transports; partial/invalid markers,
nonzero status, whitespace and error/package text are refused without echoing it.
Release acceptance performs the equivalent early guard
before reading the IME, obtaining its existing root access or reading app data.
An explicit `MIANCAK_TEST_DISPOSABLE_GUEST=true` attestation is required by those
drivers and release acceptance; the reviewed CI device steps set it only for
their fresh wiped AVDs. It grants no raw-publication permission. These checks
never clear or uninstall an existing user's data. They do not prove
freshness by themselves: CI independently provisions a new wiped named AVD.
Rejected targets produce no guest diagnostic collection. Interaction and reboot
collectors no longer request process lists or global process dumps; the
interaction collector also no longer reads global ART traces.

Build APK delivery and explicit signed release files remain separate artifacts;
they are not device-evidence staging. Private signing inputs/keys are never
included by this exporter. Local raw output must not be attached to public
issues or copied into an alternative GitHub artifact without a separate review.

Host regression checks use synthetic hostile files and fake ADB only:

```sh
python3 scripts/test_public_test_evidence.py --timeout-seconds 120
python3 scripts/test_emulator_reboot_smoke.py --timeout-seconds 120
python3 scripts/test_progress_evidence.py --timeout-seconds 90
python3 scripts/test_script_timeouts.py --timeout-seconds 120
python3 tools/release/test_device_diagnostics.py --timeout-seconds 120
python3 tools/release/generate_workflow.py --check
```
