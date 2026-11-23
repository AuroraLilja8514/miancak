# Protected preview release review

The dispatch-only workflow at `.github/workflows/protected-preview.yml`
admits the exact existing
`v0.3.4-preview.1` commit `30b074bf08ba7e668761874b41819781a3f4d44f`,
version code 7, draft 453133947. Stable v0.3.2 is code 5. The stable
trigger, gate logic, signing tools, publisher and version tags keep their
current paths.

PR #38 introduced the encrypted input and output handoff with production
held. The activation review replaces that hold with read-only admission
for the current protected `master` workflow revision, the owner account,
attempt 1, the pinned source and tree, and the current stable APK digest.
The single dispatch has no inputs and admission requires the owner account
on the protected branch. The publisher checks the exact empty draft.
[GitHub artifact access](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts)

## Candidate admission and build verification

1. Review admission and trusted helpers in a workflow-only change on
   protected `master`. The preview source already exists as a public tag.
   Its commit and tree are pinned in the preview policy. It can be admitted
   explicitly through the activation review. Review the execution and
   visible metadata below before merging the activation.
2. Review the isolated runtime in `tools/protected-preview`. Its shared
   signing, Android and watchdog helpers are pinned to the reviewed source
   `e878ac038e7fc458b0da85295fd5115a901a290f`. That snapshot supports the
   preview's API 33 installation floor and API 35 acceptance. The stable
   workflow and its original helpers stay at their existing paths.

### Private build input handoff

The reviewed policy records the owner's authorization for this exact preview.
It pins the repository, source commit and tree, registered tag, empty draft,
package and version code, production certificate, and published stable
baseline. Each production run requires the owner account on the current
protected `master` revision. The `release-signing` environment keeps its
existing branch rules and has no required reviewers.

1. **Build.** The read-only builder verifies the tag and compiles the
   production and debug APKs before applying the reviewed test-only patch.
   It compiles the instrumentation APK with that patch and uploads a
   canonical digest receipt. The receipt binds all three lengths and SHA-256
   digests, pinned tools, package identity, signing and baseline pins, run
   and attempt, candidate tree, workflow revision and test-only patch.
   Plaintext APKs and compiler output stay in the builder's staging.
2. **Verify the receipt.** A separate trusted job waits for the exact hosted
   builder and a successful receipt upload step. It validates the canonical
   receipt against the fixed release policy, rechecks the live job and the
   unchanged artifact, and attests a grant containing the receipt digest,
   artifact ID, archive checksum, builder job ID and exact invocation.
   Only the separate verification job receives OIDC and attestation-write
   permission for this grant. The digest is derived from the current build.
3. **Receive.** The signer independently verifies the grant and its
   attestation, the GitHub job and artifact identity, and the canonical
   receipt. It attests a fresh `age` recipient. The builder checks the exact
   signer and scope before sending the three allowlisted APKs as ciphertext.
   The signer requires the successful builder job, checks the transport
   digest, decrypts, and recomputes the receipt from the inspected APKs.
   The final step before credentials rechecks the grant, current master,
   freshness, and the SHA-256 of every APK, including the published baseline.
4. **Accept and publish.** The separate publisher starts after build and
   grant success. Its existing Contents-write token reads the exact empty
   draft before it issues an attested recipient. The signer verifies that
   recipient before accessing credentials. The publisher rechecks the draft
   before writing the accepted APK. A successful signed device test and an
   independent check of the accepted APK are required for that upload.

The builder's compile phase has 40 minutes and the verification job waits
up to 45 minutes for the receipt. The receipt expires for the signer after
22 minutes. The inbound ciphertext wait is four minutes, with earlier expiry
still enforced. The builder, verification, signer and publisher jobs have
70-, 48-, 42- and 50-minute limits respectively. The Android watchdog remains
1,200 seconds. Changing master, expiring the receipt or cancelling the run
closes that run's path to signing and publication.

The three previously built local inputs total 79,922,499 bytes before
transport overhead. The inbound limit is 96 MiB with exact per-file lengths
bound to the approved receipt. The signed output cap remains 64 MiB.

Host tests use real `age` on dummy bytes and synthetic GitHub API and
attestation records. Hosted dummy runs previously verified inbound and
outbound ciphertext and in-progress artifact access. The protected, signed
Android run supplies the release acceptance.
[Inbound rehearsal](https://github.com/AuroraLilja8514/miancak/actions/runs/45160602643)

Public Actions metadata includes run and job statuses, the build receipt,
attested grant, public recipients, ciphertext sizes and digests, and
attestations. Compiler, signer, emulator and guest output stay in
runner-local staging. Success and failure steps emit fixed status messages.
The release receives the single signed APK after Android acceptance.
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
hash and driver bytes to the attested receipt. The production app and driver
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
discarded. A subsequent run requires the same authenticated owner account and
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
emulation and attestation code use full commit pins. The signer runs the
pinned emulator entry point with both output streams redirected so guest
setup and failure output stays private as well. Review must confirm
the repository allows the pinned attestation action, GitHub/Sigstore
network access, and the scoped OIDC permissions. The publisher requires a
successful signer job in the exact run and attempt before reading the
ciphertext. A failed Android check prevents sealing, upload, and publication.
If run status or ciphertext
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

An explicit review-branch dispatch with the default empty input includes
an inbound rehearsal. Its builder
holds three literal dummy files. Two concurrent read-only jobs verify the
same-attempt receipt and approval while the builder is still active. A
separate reusable receiver publishes an attested public recipient only
after the gate. The builder then uploads only ciphertext and the receiver
requires real builder success before decrypting and comparing all three
dummy files. The rehearsal checks expiry, run and attempt replay,
and ciphertext tampering. It uses a same-run test approval job.

The separate cross-run rehearsal uses the same registered CI workflow on
the review branch. Dispatch `owner_approval_probe=start` as the repository
owner. The holder uploads one digest receipt calculated from literal dummy
payloads. Once the in-progress artifact is visible, dispatch
`owner_approval_probe="approve-inputs RUN_ID RECEIPT_SHA256"` on the same
revision. Both runs check the actor, workflow, repository, revision and
attempt against GitHub's API. The approval job compares the exact receipt
bytes and the holder requires the distinct approval run and its specific
job to succeed. Receipt expiry is six minutes. The holding and confirming
roles have separate concurrency groups and eight- and three-minute job
limits. If dispatch, overlap, artifact access or approval fails, the
holder expires. Statuses, the dummy receipt and its digest are visible
through GitHub Actions. Neither run compiles the app or uses the signing
environment. This rehearsal exercises the API boundary. The protected
workflow remains held for review.

## Required checks before merge

The active default-branch ruleset requires an up-to-date head, resolved
review threads and successful `generated`, `unit` and `build-debug` checks
from the PR event. A branch dispatch cannot satisfy those checks.
[Required check evaluation](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/troubleshooting-required-status-checks)

The scoped `pull_request` mode is for the draft access repair review in this
repository. It requires the owner, the same-repository
`review/repair-protected-preview-draft-access`
head, the `master` base and the first attempt. The required jobs check the
actual checkout against the event SHA and verify the test merge has the
event's base and head as parents. They run the original generated-file,
unit, debug fixture and native validations. Uploads, cache operations,
optional Android jobs and dummy smoke jobs are disabled for that PR.
The release notes proposal lives in `docs/PROTECTED_PRERELEASE_NOTES.md`
outside the release-rehearsal paths. The review head needs a normal commit
message for the PR synchronize event. Verify the exact head, test merge,
required checks and empty artifact list before requesting merge approval.
[Pull request checkout](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#pull_request)

Master pushes and other PRs use ordinary CI. The repair scope requires a
draft and the exact review branch and base. Remove the obsolete
one-PR scope in a separately reviewed workflow cleanup after this review
closes. The manual modes remain limited to that review branch until
their cleanup.

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
