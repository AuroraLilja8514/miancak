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

Signed APK bytes and raw synthetic guest data remain runner-local until the
signed Android gate passes. Raw test logs, screenshots, data hashes and
application data are never uploaded by this workflow. After the pass, a
one-day Actions artifact carries only the accepted APK and public
provenance/checksums to the separate publisher. Actions run metadata and
job status are visible on the public repository. If the review requires
the existence and outcome of the acceptance run to be private too, this
public Actions template cannot provide that privacy. Keep the template
uninstalled until an approved private Android runner and signed-artifact
handoff are available.

The publish job alone has Contents write and uses its scoped GitHub Actions
token. It rechecks the signed APK and exact tag and draft, then uploads one
APK, compares the server digest, and publishes with `prerelease: true` and
`make_latest: false`. It verifies the same asset anonymously and compares
downloaded bytes to the signed APK. A failed upload or digest leaves the draft
unpublished. A failure after publication requires checking the actual remote
state before any further write.

The ordinary public Android 15 test run targeted at the tag has a separate
purpose and remains gated on verified anonymous download of this signed
prerelease. The build and signed acceptance steps above belong to the
protected prerelease path.
