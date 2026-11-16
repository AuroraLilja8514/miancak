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
python3 scripts/test_script_timeouts.py --timeout-seconds 120
python3 tools/release/test_device_diagnostics.py --timeout-seconds 120
python3 tools/release/generate_workflow.py --check
```
