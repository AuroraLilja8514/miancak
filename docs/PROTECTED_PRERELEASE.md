# Protected preview release review

This local master-based review stages a dispatch-only workflow at
`.github/workflows/protected-preview.yml`. Its exact target is the existing
`v0.3.4-preview.1` commit `30b074bf08ba7e668761874b41819781a3f4d44f`,
version code 7, draft 453133947. Stable v0.3.2 is code 5. The stable
trigger, gate logic, signing tools, publisher and version tags keep their
current paths.

## Candidate admission and approval

1. Review the template and trusted helpers in a workflow-only change on
   protected `master`. The preview source already exists as a public tag.
   Its commit and tree are pinned in the preview policy. It can be admitted
   explicitly through this review without promoting application code to
   `master`. Install the template at `.github/workflows/protected-preview.yml`
   only after approval of the execution and artifact visibility below.
2. Review the isolated runtime in `tools/protected-preview`. Its shared
   signing, Android and watchdog helpers are pinned to the reviewed source
   `e878ac038e7fc458b0da85295fd5115a901a290f`. That snapshot supports the
   preview's API 33 installation floor and API 35 acceptance. The stable
   workflow and its original helpers stay at their existing paths.
3. The repository owner dispatches `build` on that exact reviewed
   `master` revision. An unprivileged runner builds the exact tag's app and
   the matching debug fixtures without release secrets or writable repository
   credentials. A separate runner inspects the actual compiler outputs. Its
   attestation covers the trusted workflow revision, source and tree, the
   GitHub run and artifact digest, APK identity, Build Tools hashes, and all
   three APK digests.
4. Review the unsigned output and attestation, then approve its exact digest.
   The owner dispatches `publish` from the same still-current `master`
   revision with that successful build run ID, artifact ID, and attestation
   SHA-256. Signing recomputes the entire attestation before using the
   approved bytes. The previous local unsigned APK is a reference for this
   review. A fresh runner build can produce a different digest and must be
   reviewed on its own evidence. Changed master, wrong mode or an incomplete
   build fails the pin checks.

## Signing, acceptance and artifact visibility

The protected `release-signing` job checks out the reviewed workflow revision.
It uses the existing `MIANCAK_RELEASE_KEYSTORE_B64` and
`MIANCAK_RELEASE_STORE_PASSWORD` environment secrets only in the isolated
signing step. That step runs the registered certificate challenge before it
uses the key. The candidate app is never built or executed as host tooling
on this runner. The installed candidate and the debug test driver execute
inside the disposable Android 15 guest after key-file cleanup. The production
signer remains scoped to this package and certificate.

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
network access, and the scoped OIDC permissions. Keep production dispatch
pending the synthetic verification below. If run status or ciphertext
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
