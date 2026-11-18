# Offline deployment recovery (unreleased 0.3.3)

This P3 slice makes an earlier verified deployment reachable when preparing the
new APK's offline assets fails. It retains the complete pinned Wanxiang Base and
grammar, the existing native compiler/probe and the original data paths. It does
not change dictionary weights, custom-YAML validation, schema/private policy or
learning conversion. Android minimum is API 33; actual runtime acceptance remains
Android 15+ / API 35+.

## Waiting and retry

The Chinese candidate status reports that offline data is being prepared. A first
installation without usable retained data reports a preparation failure and asks
the user to check free storage and tap the language button to retry. English direct
input remains available after that failure. No guessed percentage, storage
requirement or Android duration is presented.

If preparation fails and verified retained data exists, the idle Chinese status
reports that retained data is in use. Active composition still shows the real
preedit and candidates. Word tables shows the recovery detail and a **Retry data
preparation with saved choices** action inside its scrollable content. That action
uses the saved base, supplements and custom patches, rather than unsaved radio/
checkbox edits. Explicit Apply remains the way to apply those edits. English and
Simplified Chinese have reviewed preparation/recovery strings; other locales use
English fallback. The complete status text is the accessibility description even
when the narrow visual status is truncated. Visual and TalkBack behavior still
need actual Android acceptance.

Retry compiles and probes a separate version before publishing its selection
pointer, using the existing `WordTableStore.apply` path. Failure keeps the retained
selection and its recovery notice. Success clears the notice. It never asks the
user to uninstall, clear data, remove learning or replace custom originals.

## Failure boundary

`RimeData.identity` reads only the small packaged manifest and computes its
destination; it neither copies the large grammar nor creates data directories.
Asset preparation is separate. After a preparation I/O failure, the store chooses
a verified active managed version, a verified previous managed version, or an
intact earlier packaged bundle. Current/previous choices and custom originals stay
in place. A saved recovery pointer prevents reopening input from repeatedly
attempting the failed preparation before opening retained data. The user can
explicitly retry later. If no fallback verifies, Chinese initialization fails.

Every fallback file is checked against its manifest. A packaged `shared-<sha256>`
directory must also match the digest of its manifest bytes. Historical four-file
bundles without a persisted manifest keep the existing reconstructed-manifest
check. Arbitrary, corrupt or partial directories are not recovery candidates.
The native-open recovery path and damaged-selection recovery no longer call
large-asset preparation before reaching their retained selection.

`RimeAssetInstaller` writes a same-directory `.tmp` file, checks SHA-256, syncs its
file descriptor, then renames it over the target. Read/storage/digest failure or a
cooperative thread interruption leaves a previous complete target intact. A later
attempt overwrites a stale partial file left by process death; complete matching
assets are not rewritten. Target/staging symlinks and nonregular files are
refused. No new deployment codec or external downloader is introduced.

When full storage also prevents writing recovery metadata, verified retained
paths can still be returned without changing the on-disk selection. The current
engine carries a recovery notice. Persistence of that notice is not promised
until a write succeeds. A later reopen may therefore encounter the failed
preparation again. Learning recovery confirmation requires an applied current
Base and refuses a deferred deployment; it cannot silently run against fallback
data.

This does not guarantee recovery from every storage failure. Creating/opening the
stable user directory, reading existing data or native compilation can still
fail. Native failure keeps its bounded existing recovery attempts. Per-file sync
and rename are not a tested power-loss/directory-fsync guarantee. Retained
deployments and original learning are local recovery inputs, not a validated
off-device backup or equivalent-ranking migration.

## Verification scope

JVM tests exercise real JSON/filesystem/hash behavior with synthetic assets:
read failure, wrong digest, cooperative interruption, a precreated stale partial,
valid-file reuse, symlink refusal, first-start failure, active/previous/bundled
fallbacks, corrupt content-address refusal, full-storage metadata-write failure,
explicit failed retry and no asset preparation during native recovery. Controller
tests retain English input, typed failure/retry, notice delivery and stale-editor
protection. The test-only pinned JSON-java implementation is recorded in
`THIRD_PARTY_NOTICES.md`; APKs continue to use Android's platform JSON.

`RimeDeploymentRecoveryTest` is isolated Android coverage for patched real native
input, an injected preparation failure, failed explicit retry and successful
compile/probe/commit while preserving synthetic originals. The added
`CandidatePresentationTest` case checks actual TextViews and complete accessibility
status across English/Chinese/fallback, font scales, page labels and composition.
Compilation alone does not execute either case. Run them only through authorized
API 35+ guest/device setup and existing finite wrapper/instrumentation limits;
they complement, and do not seed or replace, ordinary first-install, upgrade,
private-input, quality and reboot scenarios.

Real Android process termination during copy/compilation, total first-deployment
wait, visible feedback, Files imports, private learning invariants, overwrite and
reboot remain in [WANXIANG_ACCEPTANCE.md](WANXIANG_ACCEPTANCE.md). A synthetic JVM
interruption does not certify Android process death, SELinux, filesystem capacity,
physical ARM performance or 16 KiB page-size runtime. Earlier setup/interaction
failures remain open until exact-source Android execution resolves them.
