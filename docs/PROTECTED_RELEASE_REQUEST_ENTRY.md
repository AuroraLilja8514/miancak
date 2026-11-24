# Protected preview request entry

The first disarmed review started at protected master
`87c9f78caf4449b14714a1706ba9f16e8c6f499f`. The connector's dummy merge
produced exactly one protected `push` rehearsal, with `AuroraLilja8514`
and numeric user ID `315702774` as actor, triggering actor and merger.
[Rehearsal run](https://github.com/AuroraLilja8514/miancak/actions/runs/45354063489)

This activation adds the protected push entry for the existing same-key
preview when merged to protected master. The owner `workflow_dispatch`
entry remains available. `manifest_request.MERGE_ENABLED` is controlled by
reviewed source. The release request is a separate protected change and
the activation contains no request file.

## Reviewed release authority

The only supported request path is
`.github/release-requests/v0.3.4-preview.1.json`, added as a regular file by a
single-parent squash from `release-request/v0.3.4-preview.1` in this
repository. The exact canonical bytes pin the reviewed parent, candidate
commit and tree, package, code, draft and prerelease tag, certificate,
application inputs and v0.3.2 baseline. A request supplies no credential,
command, destination or alternate input. The history check rejects reuse
even after a deletion. The active hosted workflow and its unique run must
match the live protected master's revision, attempt 1, the owner actor
and the original event. The two entry modes share the same non-cancelling
preview concurrency group.

The request PR's genuine `generated`, `unit` and `build-debug` jobs
verify the two test-merge parents, one-file diff, canonical bytes and
equality with the reviewed head tree. Before privileged work the script
checks the active protection, owner merger, unique associated PR and
same tree, and genuine successful jobs with zero artifacts and PR caches.
The shared request verifier uses the current job token for all twelve
GETs on the fixed repository. Each read retains a five-second timeout
and a 1 MiB response cap. The metadata client rejects redirects and
fails on the first missing credential, permission error, quota response,
or deadline. The hosted v3 rehearsal exercised this policy in each role.

| Fixed repository GET | Reads per gate | Required permission |
| --- | ---: | --- |
| `branches/master` | 2 | Contents read |
| `rules/branches/master` | 1 | Metadata read, implicit in the installation token |
| `commits/{push-sha}/pulls?per_page=2` | 1 | Pull requests read |
| `pulls/{number}` | 1 | Contents read or Pull requests read |
| `git/commits/{reviewed-head}` | 1 | Contents read |
| `actions/workflows/{pinned-id}/runs` | 2 | Actions read |
| `actions/runs/{review-run}/attempts/1/jobs` | 1 | Actions read |
| `actions/runs/{review-run}/artifacts` | 1 | Actions read |
| `actions/caches` for the checked PR merge and head refs | 2 | Actions read |

The workflow-run queries cover the production workflow at the push SHA
and the required CI workflow at the reviewed head. The rehearsal
requires zero production runs. The protected release request would
require one exact active production run. The push path makes up to
seven independent 12-read checks. Owner dispatch does not enter that
path. The repository token has its own rate limit, which still stops a
gate when exhausted.
[GitHub branch rules](https://docs.github.com/en/rest/repos/rules#get-rules-for-a-branch)
[GitHub commit-to-PR lookup](https://docs.github.com/en/rest/commits/commits#list-pull-requests-associated-with-a-commit)
[GitHub PR details](https://docs.github.com/en/rest/pulls/pulls#get-a-pull-request)
[GitHub cache API](https://docs.github.com/en/rest/actions/cache#list-github-actions-caches-for-a-repository)
[GitHub REST rate limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)

Other production metadata already uses authenticated same-repository
reads. Contents covers live refs, the candidate Git commit, the latest
stable release and the stable APK asset. Only the publisher's existing
Contents-write token reads the exact draft and can write to Releases.
Actions read covers the active run, attempt jobs, and encrypted
handoff artifact metadata and archive. Authenticated binary downloads
start at the repository API and drop the token before following a
storage redirect. The tool archives for pinned `age` and `gh` are public
downloads verified by hash. The production build still needs its public
SDK, native dependencies and emulator. Android acceptance downloads
the pinned Wanxiang data with no job credential. The exact candidate's
grammar uses the upstream GitHub Releases API asset `763629573`, so that
public download remains subject to upstream availability and anonymous
quota. The v3 metadata rehearsal does not test that download. It needs
the pinned checkout, CLI, and GitHub attestation services. An actual
release still requires the final full anonymous APK download and checksum.

## Same-run custody

- The admission, builder, grant, pre-key and publication gates independently
  check the protected request. Each job verifies its trusted tools revision
  and Git push context. Privileged work uses the existing exact receipt
  and attested same-attempt grant. The tokenless signer checks a fresh
  runner-local pre-key receipt before reading the existing credentials.
- The manifest digest, reviewed parent and tree are part of the input
  digest receipt, attested grant and recipients, encrypted payload scopes
  and sealed signed provenance. The OIDC policy checks the matching
  `buildTrigger`, workflow identity, source digest and exact run attempt.
  The owner-dispatch record shape and identity requirements are retained.
- The exact signer, Android 15 code 5 to 7 upgrade driver,
  online setup, offline typing, functional YAML and reboot gates are the
  existing ones. After they pass, the isolated signer attests the signed
  APK SHA-256 using the approved run and attempt, original request
  binding, release identity, sealed provenance SHA-256 and the checked
  acceptance fields. GitHub stores the digest attestation. Only the
  attestation bundles and age ciphertext travel in the output artifact.
  The publisher verifies the signed subject and predicate against the
  decrypted APK and sealed record before touching the draft.
- The publisher rechecks the request before attaching bytes and again
  before making the draft a non-latest prerelease. Anonymous verification
  of the actual public download remains the gate before separate,
  ordinary tag CI.

## Manual recovery after a failed publication

If an upload or publish response is lost, or the final request recheck
fails, freeze further writes. Record the exact originating run and attempt
and independently inspect release `453133947` and its asset list using an
authorized release reader. Also check that `v0.3.4-preview.1` still
resolves to candidate `30b074bf08ba7e668761874b41819781a3f4d44f` and
`v0.3.2` is still latest stable. Allow the originating run to end and
confirm that no competing run is active before considering recovery.

- **Already public with one asset.** Check the prerelease flag, exact
  download bytes and accepted APK attestation as below. Verify by an
  anonymous download before reporting success or starting ordinary tag CI.
- **Still a draft with one uploaded asset.** Check the exact asset ID,
  filename `miancak-0.3.4-preview.1.apk`, state, byte count and recorded
  SHA-256. Fetch the asset privately by that ID via the authorized GitHub
  asset interface and hash the complete bytes. Use the pinned SDK tools
  to verify the application ID, version code 7, minSdk 33 and the original
  production certificate. Retrieve the durable GitHub attestation for
  that exact digest and verify it with the pinned GH CLI and trusted roots.
  Check its signed subject and predicate against the release, original
  reviewed master and request, original digest-only input receipt and
  attested grant, baseline, and Android 15 acceptance fields. Verify the hosted signer
  job succeeded and match the attestation's issuer, caller, reusable signer,
  event, revision, runner and exact run attempt. Treat an unreadable or
  conflicting record, an incomplete asset, or multiple assets as blocked.
- With all the draft evidence verified, require a separate scoped review
  and authority for a manual publisher-only transition of that same draft
  and asset. Recheck fresh protection, source, stable, request custody
  and draft state immediately before the transition. If trusted master
  has moved or equivalent verification is unavailable, keep the draft
  held for review. The only proposed transition is the existing draft
  to a non-latest prerelease. Confirm the same asset and anonymously verify
  the complete download afterward. Keep any downloaded draft bytes private.

The ordinary publisher refuses a nonempty draft. This workflow has no
reconciliation command. A missing attestation or failed verification
leaves the release held for review with asset bytes intact.

### A new request after terminal failure and an empty draft

Record the consumed request path and digest, its protected merge and
hosted run and attempt, and the actual terminal failure stage. Inspect
the exact draft through an authorized path and require it to be empty
with no competing run. Keep the consumed request in history. If a repair
is needed, review it on protected master first. Review a separate,
exactly pinned successor entry, with a distinct request path and branch
such as `v0.3.4-preview.1-retry-1.json` and
`release-request/v0.3.4-preview.1-retry-1`. Its canonical bytes and live
admission must bind the original request and terminal run evidence, the
new reviewed base, and the same authorized release and source pins.
The successor must retain the one-file, owner, required-check, replay
and same-run signing and Android acceptance gates. It gets its own fresh
attempt 1 and full verification. Review the exact successor trigger and
ordinary CI exclusion before allowing a request PR. This entry defines
only the initial request.

## Hosted rehearsal before activation

The v1 rehearsal ran once after the protected #48 squash. Admission and
the synthetic signer each completed all twelve reads and the signer
verified its hosted proof. The publisher stopped at HTTP 403 before
creating its subject. Both reusable roles had the same scoped
permissions and read sequence. Their metadata steps ran at
11:29:20–24 UTC and 11:29:26–27 UTC respectively. The publisher's
step overlapped the signer's proof verification. The status alone
cannot establish a quota, permission, or runner-specific failure.
The protected release workflow needs overlapping signing and publishing
for the encrypted handoff, so the rehearsal keeps the existing job
dependencies. [Hosted v1 rehearsal](https://github.com/AuroraLilja8514/miancak/actions/runs/45384569065)

The v2 rehearsal ran once after the protected #50 squash. Admission
received HTTP 403 on the anonymous `branch_rules` read. Its sanitized
fields recorded `limit=60`, `remaining=0`, and `reset_epoch=1795528181`,
establishing primary quota exhaustion for that read. Both reusable roles
were skipped. The v1 publisher's failure remains unclassified.
[Hosted v2 rehearsal](https://github.com/AuroraLilja8514/miancak/actions/runs/45400868771)

The v3 rehearsal ran once after #52 added only the canonical dummy
request. Protected master was `46888c657aab1834c00442c02547e74a299a8963`.
The guard verified the owner actor and merger, protected ref, unused
request path, exact one-file tree and three genuine required PR checks.
Admission, signer and publisher each completed all twelve authenticated
metadata reads on attempt 1. The v1, v2 and v3 request paths are consumed.
[Hosted v3 rehearsal](https://github.com/AuroraLilja8514/miancak/actions/runs/45408615525)

All three dummy jobs use their own `GITHUB_TOKEN` with Contents,
Actions and Pull requests read. The caller and reusable workflows
declare the same scopes. The only write scopes are the reusable jobs'
existing OIDC and attestations permissions for fixed synthetic subjects.
The admission budget is 75 seconds in a two-minute job. The reusable
roles have 90 seconds for metadata and 135 seconds for the verifier
and pinned GH CLI download, within seven minutes per job.

On HTTP failure, the rehearsal logs the fixed category, job-token
mode, status and allowlisted numeric quota fields. A 403 or 429 stops
the run. There is no automatic retry or switch to another identity.
The response body and other header values are excluded. Confirm each
role completed all twelve authenticated reads, or record the exact
failed category and which later gates were skipped.
[GitHub rate limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)
[GitHub REST troubleshooting](https://docs.github.com/en/rest/using-the-rest-api/troubleshooting-the-rest-api)

The two reusable jobs verified their distinct fixed synthetic digests
in the `preview-readiness/v3` namespace. Both proofs bound the caller,
reusable workflow, source revision, protected ref, push event, hosted
runner and exact invocation. Both jobs rejected a different workflow,
attempt and subject. The run created no Actions artifacts or caches.
Android acceptance and release work were outside the rehearsal.

## Activation is a separate protected change

The activation enables the source gate and the literal protected-master
push trigger for the one request path. Ordinary master CI excludes that
same path. The activation PR uses `review/activate-protected-preview-request`
and the three required PR checks with uploads and caches disabled.
Verify its protected base, exact reviewed tree, scopes and owner-dispatch
entry before approving the activation. A subsequent request PR must pin
the newly verified master commit. A request merged before activation
would consume the one-shot path and stop the release flow.

The admission job adds Actions read and Pull requests read alongside
its existing Contents read. The builder, grant verifier, signer and
publisher add Pull requests read. Reusable callers and callees have
matching scopes. GitHub grants these scopes per job. The existing
`GH_TOKEN` mappings govern the metadata shell steps. The credential step
remains tokenless and consumes the fresh local pre-key receipt. The
publisher retains the only Contents-write scope.

The scoped read keeps the unique commit-to-PR lookup at each live gate.
Using the PR number from a manifest or an earlier job would require a
separate proof of unique association and freshness at the later gates.
Direct PR details alone prove only the named PR. Keeping the independent
read costs one documented read scope and preserves the gate immediately
before both draft upload and publication. Review the exact job-level
permission change and hosted v3 evidence before any production work.
[GitHub job permissions](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#permissions)
[GitHub reusable workflow permission inheritance](https://docs.github.com/en/actions/how-tos/reuse-automations/reuse-workflows#nesting-reusable-workflows)

The release still requires the exact same-key version-code 5 to 7
overwrite on Android 15, authenticated custody, online pack setup,
offline editor output, working custom YAML, learning preservation and
a real reboot. The preview remains gated on that full signed acceptance.
