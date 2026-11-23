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

Use one production run for build, signing and publication, with a separate
owner-only digest approval dispatch. This lets the build and signing
runners use a fresh `age` identity without copying a private decryption
key between runs. The current `release-signing` environment limits branches
and has no required reviewer. The digest approval gate must run outside that
environment and succeed before the signing job can start.

1. **Build.** On the exact reviewed `master` revision, compile the tagged
   candidate and debug driver on an unprivileged runner. Keep all three
   APKs in private temporary storage. Publish only a strictly typed receipt
   with the trusted workflow revision, exact tag commit and tree, application
   fingerprint, run and attempt, Build Tools hashes, expected package and
   version, and the names, lengths and SHA-256 hashes of the three APKs.
   Record the SHA-256 of the canonical receipt. Keep compiler output and
   archives local. The job waits for a recipient from the authorized signer.
2. **Approve.** A concurrent, read-only job validates the receipt and waits
   for the owner to review it and dispatch a dedicated `approve-digest` mode
   with the production run ID and receipt hash. That mode has a single
   no-secrets validation job and a fixed run title that binds the two inputs.
   Use the authenticated Actions API to require that exact workflow and
   completed successful approval job on the same current `master` revision.
   Check the owner account ID in both actor fields, repository ID, branch,
   workflow path, event, run title, and first attempt, with the approval
   created after the receipt. Fail on missing, mismatched, duplicate or
   replayed approvals. A build job output alone cannot satisfy this gate.
3. **Receive and verify.** Only after the approval job succeeds, start the
   protected signing job. It generates a runner-local `age` identity and
   attests the public recipient with an input-specific role tied to the
   approved receipt and this exact run and attempt. The build runner
   authenticates it with the existing same-attempt transport checks. It seals
   the three allowlisted APKs and the canonical receipt, uploads only the
   ciphertext, clears plaintext staging and finishes successfully. The
   signer verifies the origin and archive digest, waits for build success,
   decrypts in temporary storage and independently inspects the actual
   APKs and Build Tools. Require all digests and the canonical receipt to
   match the owner approval before the credential-bearing step. Check the
   current master and tag again before using the key.
4. **Publish.** Start the publisher after both approval and build success.
   The signer authenticates that second recipient before signing. It uses
   the independent output transfer and acceptance gates below. Delaying the
   publisher lets two hosted runners suffice throughout the run. Give the
   receipt, owner review, transfer and cancellation paths finite deadlines.
   Run approval in its own concurrency group so it can finish while the
   production run waits. A timeout or lost runner requires a fresh build
   and approval, including if only a debug fixture digest changes.

The preserved local inputs total 79,922,499 bytes before transport overhead.
Use a separate input archive limit, for example 96 MiB, with per-file
lengths bound by the receipt. The 64 MiB signed-output cap is too small
for this input transfer and should stay scoped to its existing role.

This design is a proposal. Exercise the new approval and incoming transfer
with only synthetic data before enabling real inputs. The successful
synthetic run below exercised the other direction. A configured, explicitly
approved private runner and transfer service could also hold the three
inputs, provided it enforces the same byte comparison and pre-key owner
approval. A registered private runner or storage integration has not been
verified here.

Public Actions would expose workflow and job statuses, typed receipt
metadata and hashes, approval IDs, the public recipients, ciphertext sizes
and attestation metadata. Approve this boundary before production
execution. Keep raw logs, APKs, signing data and acceptance evidence
outside summaries, caches and unencrypted artifacts. Check an uploaded
APK against authenticated draft metadata before changing release state.
[Actions workflow run metadata](https://docs.github.com/en/rest/actions/workflow-runs)

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

Keep the existing signed smoke intact. Add the following on the guarded,
disposable Android 15 guest after the normal signed upgrade, offline editor
checks and real reboot. The pinned candidate needs no application change.
Test the shipped picker and actual receiving editor. Directly seeding a
compiled schema, selection JSON or app-private catalog cannot pass.

1. Use the existing signed code-7 package. Confirm the app is using the
   pinned Wanxiang Base and grammar. In an ordinary receiving editor,
   confirm `nihao` commits `你好` with the default spelling. Record whether
   `nihc` already offers the same result so the spelling probe has a control.
2. Place the synthetic file below in Android Downloads. Open **Settings →
   Chinese word tables → Import Rime configuration** and choose
   `wanxiang.custom.yaml` through the normal Files picker. Check that the
   imported original's bytes and digest match, and that its checkbox starts
   clear. Confirm the import alone leaves the active selection unchanged.

   ```yaml
   patch:
     speller/algebra:
       __patch: [wanxiang_algebra:/base/小鹤双拼]
   ```

3. Select the imported file and apply word tables. Wait for the successful
   deployment. Check the selected digest and managed slot. Offline in a
   real receiving editor, type `nihc`, explicitly select `你好` and require
   exact committed text. If the unpatched control could already produce this
   text, also verify the compiled spelling change in the active slot.
4. Force-stop and reopen, then reboot and require a changed boot ID. Repeat
   the offline check without reimporting or applying. Verify the managed
   original and the legacy file both survived. Verify a null default
   Android network and no usable external route for the tested guest before
   the offline assertions. Keep the raw observations local. Tests already
   present in `RimeConfigNativeTest` use a debug-only isolated store and
   provide a supplement.

The release report must require a typed
`custom_yaml_activated_after_reboot: true` from the trusted guest check.
The byte-retention-only driver cannot produce that field. The marker and
sequence are prepared for review. The pinned host parser accepts the
synthetic marker (SHA-256
`f9c8c56de2cba0025fc65d76db00b05006ed4d6774417238b565472a37598c6b`).
The import, editor and reboot checks await Android execution.

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

The workflow concurrency group admits one active preview run. Publish mode
needs capacity for two runners at once. The signer must see its running
publisher and a finalized recipient within four minutes. The publisher
waits at most 43 minutes for successful signed acceptance and ciphertext.
The two jobs have 42- and 50-minute limits. The device smoke keeps its
original 1,200-second watchdog. Missing overlap, API access, valid proofs,
or acceptance abort the handoff. There is no plaintext fallback.

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

## Required checks before merge

The active default-branch ruleset requires an up-to-date head, resolved
review threads and real `generated`, `unit` and `build-debug` check results.
The skipped draft has no check runs. A successful synthetic run on the
same tree under another commit cannot satisfy the required checks. Current
`ci.yml` starts the broad workflow on both a PR and a manual dispatch. It
uploads debug and instrumentation APKs, fixtures and an unsigned app.
That makes it unsuitable for a metadata-only review.

For a separate review, add an explicit review-only input to the existing
`ci.yml` manual dispatch on the exact workflow review branch. Run the
original steps of all three required checks, including both debug fixture
builds and native validation, with artifact uploads disabled. Skip the other
jobs only in this scoped mode. Leave normal PR and master push behavior
unchanged. Keep the review HEAD marked `[skip ci]`, so its PR does not start
CI before the approved dispatch. Review the effective job list and confirm
the actual passed checks at the commit GitHub is requiring before merge.
Check that the base and protected application inputs are unchanged. Do not
stand in required status checks, skip required jobs or bypass the ruleset.

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
