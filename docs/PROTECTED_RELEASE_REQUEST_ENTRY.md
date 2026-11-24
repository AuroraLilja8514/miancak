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
GitHub serves these public records without a token. The existing
job permissions remain unchanged. GitHub reserves the ruleset bypass
list for writers. The runtime check relies on the actual reviewed tree
and required job results, and the normal activation review must check
the active ruleset independently.
[GitHub ruleset API](https://docs.github.com/en/rest/repos/rules#get-a-repository-ruleset)

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
  existing ones. The publisher rechecks the request before attaching bytes
  to the draft and before making it a non-latest prerelease. The existing
  anonymous download verification remains the check before separate,
  ordinary public tag CI.

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
