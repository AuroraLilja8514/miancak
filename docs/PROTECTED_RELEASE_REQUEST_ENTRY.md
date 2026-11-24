# Protected preview request entry

The disarmed review starts at protected master
`87c9f78caf4449b14714a1706ba9f16e8c6f499f`. The connector's dummy merge
produced exactly one protected `push` rehearsal, with `AuroraLilja8514`
and numeric user ID `315702774` as actor, triggering actor and merger.
[Rehearsal run](https://github.com/AuroraLilja8514/miancak/actions/runs/45354063489)

This change prepares the route for the existing same-key preview. The live
entry remains owner `workflow_dispatch`. The workflow has no push trigger,
`manifest_request.MERGE_ENABLED` is false in source, and no release request
exists in this diff. Neither a workflow input nor a repository variable can
enable it. The dummy workflow remains read-only.

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
The existing job permissions remain unchanged. Each gate uses its own
job token for bounded same-repository Contents reads. Gates with
`actions: read` also use that token for the scoped Actions runs, jobs,
artifacts and cache reads. Rules and pull request reads stay anonymous.
The admission job has Contents read only and makes nine anonymous reads.
Each of the other six gates makes three, for 27 anonymous request reads
per complete push attempt. This count excludes other jobs on the runner
IP. Each read uses a five-second timeout and a 1 MiB response cap.
Missing authorization, limits and deadlines fail the gate. Verify the
effective ruleset independently during activation review.
[GitHub ruleset API](https://docs.github.com/en/rest/repos/rules#get-a-repository-ruleset)
[GitHub REST rate limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)

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

Use a separate, disarmed review to add a literal one-file trigger for
`.github/release-requests/_protected-preview-readiness-probe.json`.
Review a matching ordinary master CI path exclusion and genuine
no-upload PR checks for the exact rehearsal review and dummy request
branches. Keep this workflow's source gate and owner-dispatch trigger
unchanged. A normally protected dummy squash should produce one run
whose caller verifies the actual repository, protected ref, exact owner
actor ID, reviewed one-file merge and attempt 1.

Exercise the real metadata router in the Contents-only admission role
and in two reusable jobs with Contents and Actions read. Use fixed public
PR, protection, run, job, artifact and cache queries, including the
production workflow's push-list query at the dummy SHA. Confirm the
expected empty production result and normal response shapes. Check
both role budgets under GitHub-hosted egress. The admission budget is
75 seconds in a two-minute job. The other roles' metadata probes must
fit their 90-second step envelopes. Record only fixed phase/status,
request counts, bounded timing and any available public quota headers.
Treat rate limiting, timeout and unavailable metadata as failed evidence.

In the same rehearsal run, use separate local reusable signer and
publisher jobs with read-only repository permissions plus only the
OIDC and attestation permissions needed for dummy digest attestations.
Use a dummy predicate namespace, fixed dummy bytes and the pinned
official attestation and verifier tooling. Verify both real certificates
against the caller and reusable workflow paths, source revision,
protected ref, `push`, GitHub-hosted runner identity and exact invocation.
Prove that wrong workflow, attempt and subject fail. Confirm that the
run produces only dummy attestations and job metadata. The rehearsal
jobs have no signing environment, credentials, APK build, archive
upload, cache or release write. Its hosted routing budgets and OIDC
evidence remain pending.

## Activation is a separate protected change

A future reviewed activation must enable the source gate, add the literal
protected-master push trigger for the one request path to
`protected-preview.yml`, and add that same path to ordinary CI's master
push exclusions. Review the corresponding trigger assertions with that
change. The activation adds no request file. Verify the exact
protected branch, permission scope and unchanged owner-dispatch entry
before opening the separate request PR. A request merged before activation
would consume the one-shot path and must stop the release flow.

This draft's no-upload review branch is
`review/protected-preview-merge-entry`. The real PR jobs run, and local
tests exercise the admitted and rejected entry paths using disposable Git
trees and synthetic API observations. The signed Android 15 run and push
attestation issuance remain untested in this disarmed review.
