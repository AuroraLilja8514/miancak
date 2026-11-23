# Protected preview release review

This local master-based review stages a dispatch-only workflow at
`.github/workflows/protected-preview.yml`. Its exact target is the existing
`v0.3.4-preview.1` commit `30b074bf08ba7e668761874b41819781a3f4d44f`,
version code 7, draft 453133947. Stable v0.3.2 is code 5. The stable
trigger, gate logic, signing tools, publisher and version tags keep their
current paths.

The production modes in this revision stop at `private-input-review`. The
build path reviewed in PR #38 would upload the three unencrypted build
inputs for seven days. A public repository's artifacts can be downloaded
by signed-in readers. The encrypted output handoff does not protect that
earlier upload. The production modes stay held until the private input
transport and signed acceptance described here are implemented and reviewed.
[GitHub artifact access](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts)

## Candidate admission and approval

1. Review the template and trusted helpers in a workflow-only change on
   protected `master`. The preview source already exists as a public tag.
   Its commit and tree are pinned in the preview policy. It can be admitted
   explicitly through this review without promoting application code to
   `master`. Review the execution and visible metadata below before enabling
   production modes.
2. Review the isolated runtime in `tools/protected-preview`. Its shared
   signing, Android and watchdog helpers are pinned to the reviewed source
   `e878ac038e7fc458b0da85295fd5115a901a290f`. That snapshot supports the
   preview's API 33 installation floor and API 35 acceptance. The stable
   workflow and its original helpers stay at their existing paths.

### Private build input handoff

The local implementation uses one owner-dispatched production run with a
separate `approve-inputs` dispatch. The approval job has read-only permissions
and no signing environment. The current `release-signing` environment
limits branches and has no required reviewers.

1. **Build.** The unprivileged builder verifies the tag and compiles the
   production and debug APKs before applying the reviewed test-only patch.
   It then compiles the instrumentation APK from the same candidate with
   that patch. It publishes a bounded canonical JSON receipt of the three
   exact lengths and digests, pinned SDK tools, package, build run and
   attempt, candidate commit/tree/application fingerprint, trusted workflow
   SHA, and test-only patch hash. The APKs and compiler log stay in the
   builder's temporary directory. The receipt digest is in the completed
   receipt step's log and its metadata-only artifact, so review can start
   while the builder waits.
2. **Approve.** The owner reviews the receipt and dispatches
   `approve-inputs` with that live run ID and the canonical receipt SHA-256.
   A separate job checks the active production run and digest. The
   authorization gate verifies its exact workflow, owner account ID in
   both actor fields, source branch and SHA, attempt, title, completion,
   and unique successful approval job. The approval must occur after the
   receipt and within 15 minutes. Both dispatches use the current protected
   master. A separate concurrency group lets the approval run start while
   the production run waits.
3. **Receive.** The signer starts only after the read-only authorization
   job passes. It uses a distinct reusable workflow to attest a fresh
   `age` recipient. The builder authenticates the signer and recipient
   against the exact workflow revision, invocation, attempt and transport
   role, then sends the three allowlisted files as ciphertext. The signer
   requires the exact successful build job before downloading, verifies
   the ZIP digest and scope, decrypts, and recomputes the complete approved
   receipt from the actual APKs. The APKs are independently inspected
   again. The read-only pre-key step confirms the owner grant, receipt
   freshness, current master and all three local APK hashes. The signing
   step receives the existing credentials and also binds the same bytes.
4. **Accept and publish.** The publisher starts after build and approval
   success. The signer verifies its independent recipient before signing.
   The output follows the encrypted handoff and device gates below.

The builder's compile phase has 40 minutes and the receipt wait is capped
at 45 minutes. The owner has 15 minutes from receipt creation. The receipt
expires for the signer after 22 minutes. The inbound ciphertext wait is
four minutes, with earlier expiry still enforced. Job ceilings are 70
minutes for the builder, 63 for authorization, three for the approval
request, 42 for the signer and 50 for the publisher. The Android watchdog
remains 1,200 seconds. Three hosted-runner slots may be needed briefly
during owner approval. Waiting for approval or changing master may cause
expiry, which requires a fresh compile and owner review.

The three previously built local inputs total 79,922,499 bytes before
transport overhead. The inbound limit is 96 MiB with exact per-file lengths
bound to the approved receipt. The signed output cap remains 64 MiB.

The local tests exercise this path with dummy bytes, real `age` and
simulated GitHub API records. The production modes stop at
`private-input-review`. Before enabling them, validate the owner approval
and new direction on GitHub in a separate synthetic-only run without
the signing environment or a Contents-write token. That run needs to
confirm the three-runner overlap, authoritative owner and attempt metadata,
in-progress artifact access, replay and expiry rejection, and decryption.
The previous synthetic run verified the outgoing direction.

Public Actions would expose run and job statuses, the typed build receipt,
approval run IDs, public recipients, encrypted archive sizes and digest,
and attestation metadata. The owner needs to approve that visibility.
Raw compiler and guest output, APKs, signing data and acceptance diagnostics
stay in runner-local staging. Use a verified private runner or storage route
if this visibility is unsuitable. Private runner availability is unverified.
[Workflow run metadata](https://docs.github.com/en/rest/actions/workflow-runs)

## Signing, acceptance and artifact visibility

The protected `release-signing` job checks out the reviewed workflow revision.
It uses the existing `MIANCAK_RELEASE_KEYSTORE_B64` and
`MIANCAK_RELEASE_STORE_PASSWORD` environment secrets only in the isolated
signing step. That step runs the registered certificate challenge before it
uses the key. The candidate app is never built or executed as host tooling
on this runner. The installed candidate and the debug test driver execute
inside the disposable Android 15 guest after key-file cleanup. Build Tools
must find precisely one instrumentation declaration for the debug package
and the expected AndroidJUnitRunner. Before installation, recheck the
already approved APK and both fixture digests. The candidate driver's UI
assertions run in the guest. The reviewed host code binds their results to
the actual signed APK and independently verifies the data and reboot.
Use the API 35 Google APIs x86_64 image with emulator build 16428233 and
`swangle`, as used by the ordinary CI recipe.
[Prior API 35 CI run](https://github.com/AuroraLilja8514/miancak/actions/runs/43833769359)

The same runner retains the signed bytes through acceptance. The pinned
v0.3.2 APK is installed, used to set real preferences and generate learning,
and then replaced with version code 7 through an in-place install. The
original device smoke enforces actual receiver text, data snapshots, split
pack setup via the app, no active Android default network at typing time,
and a changed boot ID. A synthetic legacy `.custom.yaml` file in the old
Rime user directory must survive overwrite, setup and reboot. The downloaded
grammar digest is checked in the active resource directory after reboot. This tests
legacy YAML byte retention. The old release's catalog import activation and
the preview's new custom YAML UI are separate acceptance cases.

### Signed custom YAML acceptance

Only the independently debug-signed instrumentation fixture gets the
reviewed overlay in `tools/protected-preview/release-yaml-driver.patch`.
The exact candidate source and the app APKs are built before applying it.
The signer checks the actual instrumentation target and binds the overlay
hash and driver bytes to the owner receipt. The production app and driver
execute in the disposable guest after key-file cleanup.

The guarded host driver creates a synthetic `wanxiang.custom.yaml` in
Android Downloads after the signed v0.3.2 overwrite. It writes only
the picker source. The overlay uses the actual signed app's Settings and
Files picker. It requires the new source's fingerprint to appear and its
checkbox to start clear, then selects it and applies the deployment.

```yaml
# Synthetic preview acceptance
patch:
  'switches/@7/reset': 1
```

The original receiver checks remain. On the same ordinary editor, the
overlay types `zhongguo` and requires the explicit candidate tap to
commit `中国` first. After applying the YAML it requires `中國`.
After the existing real reboot it repeats that exact commit offline without
reimport or reapplication. The host checks the managed source bytes and
the same selected slot across reboot, in addition to the original legacy
YAML, model, preference and learning checks. A typed
`custom_yaml_activated_after_reboot` receipt reaches the publisher only
after these checks finish. The marker uses the Traditional switch already
exercised by the pinned native host suite. Local compilation and host
fixtures leave the actual Android run pending.

The public Actions handoff uses two independently scheduled, fresh hosted
runners. The reusable publisher workflow comes from the caller's exact
commit and generates a single-run `age` identity. Its private identity file
stays on that runner, outside every upload path. It publishes only the scoped
recipient JSON and its GitHub/Sigstore attestation. The signing job requires
a live publisher from this attempt and authenticates the recipient before
the signing step. It checks the verified certificate extensions for the
reusable publisher identity, repository ID, caller, source and workflow
digests, GitHub-hosted runner, and exact invocation URI with run and attempt.
Matching artifact names and JSON fields alone are insufficient. The protected
signer and publisher require OIDC and attestation permissions to generate
these proofs. The publisher alone has Contents write. Each job uses its
scoped Actions token and this introduces no persistent credential.

After the signed Android gate passes, the signer packs only the accepted
APK, sealed provenance/checksums and a receipt binding the exact recipient
into a size-bounded archive. The public handoff carries the `age` ciphertext
and the protected workflow's attestation of its digest. The publisher
requires the same run attempt's unique successful acceptance job, verifies
the transport digest and signed workflow identity, decrypts locally, and
checks the accepted APK and receipt again. Both jobs discard private staging.
Raw guest logs, screenshots, preference and learning snapshots, signing
credentials, and the private age identity are outside all artifact paths and
job outputs. Public readers can see the run status, recipient, ciphertext,
and attestation and transparency log metadata.

The publisher attaches one verified APK to the exact existing draft. It
checks the server digest before publishing with `prerelease: true` and
`make_latest: false`. It verifies the result by anonymous full download and
checksum. An upload or digest error leaves the draft unpublished. A failure
after publication requires checking the actual remote state before any
further write. If a job exits early, the publisher's private identity is
discarded. A subsequent attempt requires a new owner-approved dispatch and
a known draft state.

The workflow concurrency group admits one active preview production run.
After the private inbound transfer, the signer and publisher overlap.
The signer waits up to four minutes for a running publisher's recipient.
The publisher waits up to 43 minutes for successful signed acceptance and
ciphertext, subject to its job ceiling. Missing overlap, API access, valid
proof or acceptance aborts delivery.

The tool installer fetches the official Linux x86-64 release archives for
`age` 1.3.2 and `gh` 2.105.0 over HTTPS and checks their exact sizes and
SHA-256 hashes before extracting only named binaries. Checkout, upload,
emulation and attestation actions use full commit pins. Review must confirm
the repository allows the pinned attestation action, GitHub/Sigstore
network access, and the scoped OIDC permissions. Keep production modes held
until the input transport and signed checks pass. If run status or ciphertext
must also stay private, use an approved private execution and custody
route before signing.

[Verified attestation claims](https://cli.github.com/manual/gh_attestation_verify) · [Fulcio invocation identity](https://github.com/sigstore/fulcio/blob/main/docs/oid-info.md) · [Artifacts during a run](https://github.blog/news-insights/product-news/get-started-with-v4-of-github-actions-artifacts/#immediate-public-api-availability)

## Opt-in synthetic Actions verification

The first push creating `review/protected-preview-ciphertext` opts into
`protected-preview-smoke.yml`. Its reusable receiver executes the same
handoff checks under a distinct workflow, source ref, push event and
artifact names. Each job gets Contents read, Actions read, OIDC and
attestation permissions. The workflow checks out only the trusted tooling
and operates on a literal dummy payload. Its two short-lived artifacts each
contain an attestation bundle and either the public age recipient or the
encrypted payload. The private age identity stays in the receiver's job
directory and is cleaned up at job exit.

The sender requires the receiver to be running while it lists, downloads
and authenticates the recipient. It tests cross-run and cross-attempt
replay and ambiguous names. The receiver requires a successful sender
in the same attempt, verifies the ciphertext proof, compares the decrypted
bytes and digest, and rejects a changed ciphertext and a wrong key. Local
tests separately exercise queued and failed jobs, absent overlap and
untrusted workflow identities. The runner logs contain pass and failure
markers for synthetic operations. Production signing, Android acceptance
and publishing need their own authorized runs.

Use a same-tree `[skip ci]` head for the draft review so the repository's
broad pull-request trigger omits ordinary Android CI. This leaves the
required checks pending for review. Preserve the tested tree and link the
exact push run from the draft. [GitHub workflow skip behavior](https://docs.github.com/actions/managing-workflow-runs/skipping-workflow-runs)

The same manual check-only run includes an inbound rehearsal. Its builder
holds three literal dummy files. Two concurrent read-only jobs verify the
same-attempt receipt and approval while the builder is still active. A
separate reusable receiver publishes an attested public recipient only
after the gate. The builder then uploads only ciphertext and the receiver
requires real builder success before decrypting and comparing all three
dummy files. The rehearsal checks expiry, run and attempt replay,
and ciphertext tampering. It uses a same-run test approval job. The
distinct production owner dispatch still needs verification before
enabling production modes.

## Required checks before merge

The active default-branch ruleset requires an up-to-date head, resolved
review threads and real `generated`, `unit` and `build-debug` check results.
The skipped draft has no check runs. A successful synthetic run on the
same tree under another commit cannot satisfy the required checks. Current
`ci.yml` starts the broad workflow on both a PR and a manual dispatch. It
uploads debug and instrumentation APKs, fixtures and an unsigned app.
That makes it unsuitable for a metadata-only review.

The local `ci.yml` revision scopes review-only mode to an explicit manual
dispatch on `review/protected-preview-ciphertext`. It uses the already
registered workflow and takes no new dispatch inputs. It runs the original
steps of all three required jobs, including both debug fixture builds and
native validation. It disables required-check uploads, caches and ordinary
optional jobs for this mode. The inbound rehearsal publishes only its
dummy receipt, public recipient/proof and ciphertext. Normal PR and master
push jobs keep their existing behavior. Each required job checks the owner, repository, branch, attempt,
workflow SHA, and checked-out SHA. Retain the `[skip ci]` marker at the exact
review HEAD, then recheck the branch and PR head before and after the
authorized manual run. GitHub documents that manual branch dispatch checks
the last commit on the given ref. Verify the three successful GitHub
Actions check runs on the PR head, the dummy-only artifacts list, and the absence
of check results on a competing test-merge commit before marking ready.
Actual attachment to #38 awaits that run.
[Dispatch source SHA](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_dispatch)

When the owner approves a merge after those checks, use a squash or merge
message that explicitly carries `[skip ci]` on the new master commit.
Confirm the marker is in the actual commit message GitHub will use. This
avoids the post-merge master push build. The existing stable publisher
listens for successful master push CI. It does not accept a review branch
dispatch as a release source. The ordinary master run's early uploads would
occur even if the stable publisher later reported an already-used version.
This path requires separate review of the scoped CI mode and the merge.
[Required check commit selection](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/troubleshooting-required-status-checks)

## Upgrade from this preview to stable

Reserve production code 7 for the signed preview. The next stable APK needs
code 8 or higher, `org.miancak.keyboard`, and the same registered release
certificate. Android rejects a normal reinstall of v0.3.2 code 5 over code
7. Keeping v0.3.2 latest on GitHub does not change package-manager ordering.
The current stable gate compares only against that latest stable release,
so it does not enforce the floor after code 7. This review leaves that
pipeline intact.

When the preview has shipped, pin the exact released code-7 APK digest and
certificate as an additional stable acceptance fixture alongside v0.3.2.
On a fresh disposable Android 15 guest, exercise the signed preview and
preserve synthetic settings, learning and YAML. Overwrite it with the
higher-code same-signer stable APK, confirm the installed code and real
input, and check those data and offline Chinese setup after a real reboot.
Do not treat the separately signed debug APK as an upgrade fixture.

[Android version code and downgrade rules](https://developer.android.com/studio/publish/versioning#versionSettings)

The ordinary public Android 15 test run targeted at the tag has a separate
purpose and remains gated on verified anonymous download of this signed
prerelease. The build and signed acceptance steps above belong to the
protected prerelease path.
