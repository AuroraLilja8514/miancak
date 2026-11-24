# Release requests on protected master

This local evaluation starts at `807818ad46a0a3d7007c0937c32649ac0925a230`.
It includes an offline admission prototype. The deployed workflow still uses
owner `workflow_dispatch`. The proposed request is limited to the approved
`v0.3.4-preview.1` candidate `30b074bf08ba7e668761874b41819781a3f4d44f`,
draft `453133947`, code 7 and the registered v0.3.2 signing certificate.

## Preferred path

Bootstrap an exact-path `push` trigger on protected `master` through a
reviewed workflow PR. After that, each approved release request is a PR
adding one immutable manifest. The preview path for this proposal is
`.github/release-requests/v0.3.4-preview.1.json`. The policy comparison
requires the approved candidate commit, tree, application-input digest,
tag, draft, package, code, baseline APK digest, certificate, delivery
manifest digest and the exact parent reviewed by the PR. The request cannot
select a workflow, destination, command, credential or alternate input.

The bootstrap has two coordinated trigger changes. The protected workflow
uses the fixed `master` push and literal path. Ordinary `ci.yml` ignores that
literal path in its **push** trigger. Its actual PR checks still run against
the manifest PR and use the existing no-upload, no-cache draft review mode
scoped to the exact same-repository head. Other ordinary changes retain
their existing CI and release rules. The bootstrap PR can use `[skip ci]`
on its squash commit because it adds no request. The request squash commit
must omit that marker so the protected `push` trigger runs. This avoids
opening the default-branch workflow to untrusted PR execution.
[GitHub workflow triggers](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow)
[GitHub skip instructions](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/skip-workflow-runs)

### Read-only admission

Use trusted runner and GitHub API observations with the following checks
before any build, secret-bearing job or publisher can proceed.

1. Verify the repository name and numeric ID, workflow ID and path, `push`
   event, protected `refs/heads/master`, attempt 1 and current run. Require
   the context, hosted run revision, trusted checkout and live `master` to
   identify the same commit. Require a single non-forced update whose
   `before` is the commit's only parent. Resolve the changed paths from the
   actual Git trees. The diff must add one regular file at the exact request
   path. Check the parent's complete history for earlier use of that path.
2. Resolve the unique associated PR for that commit. Require it to be
   merged from the fixed same-repository request branch into `master`, with
   the exact reviewed base, matching merge commit and manifest bytes from
   its reviewed head. Verify the trusted GitHub `merged_by`, event sender,
   run actor and triggering actor by login and numeric ID. The prototype
   accepts only Audrey's existing owner identity. The proof uses GitHub's
   actor metadata. A Git commit author or manifest field cannot supply it.
3. Verify the genuine `generated`, `unit` and `build-debug` checks succeeded
   on the exact PR and reviewed tree without uploads or caches. At minimum,
   use the known workflow and exact PR check run plus the existing guarded
   checkout check. Have its trusted `generated` step emit a fixed line with
   PR number, base, head, test-merge SHA and tree hash. Resolve that run and
   job through GitHub and verify the line and tree against the resulting
   commit. Audit the raw event and run field shapes on a dummy merge before
   implementing this adapter. A commit status or the manifest by itself
   cannot establish this proof.
4. Reject an earlier request path, ambiguous PR associations, ambiguous
   runs for that workflow event and merge commit, and reruns. Keep the
   existing release-specific concurrency group. Bind the request digest,
   merge SHA and run attempt to the existing grant and verify them again
   before key access and publication. Preserve the publisher's independent
   exact empty draft and current stable checks. An expired or failed request
   remains in history. Any subsequent attempt needs a separately reviewed,
   uniquely addressed authorization.

Use a read-only first job. Its eventual adapter needs only the read access
required to inspect Contents, Actions and PR metadata. The signer and the
publisher retain their current distinct job permissions. Reuse the existing
encrypted handoffs and signed Android 15 acceptance for the real in-place
upgrade, online pack, functional YAML, offline input and reboot. Publish
only the pinned signed asset on the exact non-latest prerelease after those
checks and verify the anonymous download. Ordinary tag CI follows that
verification. Select only one production authority for this preview so the
old manual entry point cannot race the manifest route.

### Bootstrap evidence

The current connector returned Audrey as `AuroraLilja8514` with numeric ID
`315702774` and repository permission `admin`. Its installation `152856073`
lists `AuroraLilja8514/miancak` and can read the deployed
`.github/workflows/protected-preview.yml`, blob
`618cc8c1245d6c053c9562182a02f56332186abf`. It already created, marked ready and
squash-merged PR #43 after the required checks. The review branch was
previously pushed by the CLI. That evidence covers the PR operations and
workflow read access. GitHub's timeline identifies Audrey as the merger.
That squash used `[skip ci]`, so it supplies no `push` workflow actor
observation. [PR #43](https://github.com/AuroraLilja8514/miancak/pull/43)

The connector created `probe/workflow-bootstrap-20261124` from live
`master` at `807818ad46a0a3d7007c0937c32649ac0925a230`. One contents API
call added `.github/workflows/miancak-workflow-write-probe.yml` in
`b1b3b6f851ea3b25841d860fede33830aec9eb42`. Its only event is
`workflow_call`, and its only job has a constant false condition. The
workflows at the branch's starting commit had no matching branch creation
or push trigger. GitHub confirmed the commit's parent and sole added
file, and reported zero runs by branch and by SHA. The connector offers
file deletion and ref updates. Branch deletion needs a supported separate
operation, so the inert ref remains.
[Probe commit](https://github.com/AuroraLilja8514/miancak/commit/b1b3b6f851ea3b25841d860fede33830aec9eb42)

The probe establishes the connector's actual workflow-file creation
capability in this repository. Updating an existing workflow through the
contents API has the same documented permission requirements. Bootstrap
edits still need their normal approval and exact protected PR checks.
Release manifests can use the ordinary contents operation outside
`.github/workflows`.
[GitHub contents API permissions](https://docs.github.com/en/rest/repos/contents#create-or-update-file-contents)

GitHub documents that installation-authenticated operations can trigger
workflows. The connector already triggered required PR checks in this repo.
The exact `master` push actor and delivery still need a dummy rehearsal.
An inert bootstrap can reserve a separate literal dummy path, with matching
CI push exclusion and a read-only job that has no signing path, secrets or
artifacts. Merge a synthetic request through the same connector and check
the actual issuer, protected ref, run attempt, merged PR and same-tree CI
evidence. If the event appears under an app identity, require verified owner
authorization for the exact checked PR before pinning any alternate event
actor. Enabling a generic bot or relying on the app name would drop the
current identity assurance.
[GitHub installation-triggered workflows](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow#triggering-a-workflow-from-a-workflow)

### Disarmed bootstrap rehearsal

The local bootstrap adds one read-only workflow and extends CI's existing
no-upload review mode. The preview dispatcher, its signer and publisher,
and the stable release workflow retain their existing entry points. The
new workflow accepts only `.github/release-requests/_protected-bootstrap-probe.json`.
The dummy holds a format version, a literal rehearsal kind and the
reviewed base commit. It has no release inputs or privileged jobs.

1. Stage the bootstrap on `review/bootstrap-protected-release-request`
   from the verified current `master`. Complete the exact reviewed diff
   before opening its draft PR. Review the triggers on that base first.
   Staging pushes match no existing push filter. The draft PR runs the
   genuine `generated`, `unit` and `build-debug` jobs. The other CI jobs,
   all caches and all uploads stay disabled for the scoped review. Verify
   the check run's exact PR head and zero artifacts. After normal
   protection checks, squash the bootstrap with `[skip ci]` in the actual
   commit message. This bootstrap adds no request file.
2. From the resulting master revision, stage a separate draft PR on
   `release-request/dummy-protected-merge` adding only the canonical dummy
   file. Generate its bytes with `manifest_rehearsal.canonical_dummy`
   using that exact base SHA. In each of the three required jobs, the
   extra step verifies a sole-parent request head, the real test merge's
   two parents, identical head and test-merge trees, file mode and exact
   contents. A stale or multi-file PR fails.
3. After the protected checks pass, squash the dummy PR with an ordinary
   message so the exact-path push workflow starts. CI's `push` exclusion
   upholds the no-upload boundary for this single path. This workflow
   has no dependency on a production environment or signing, and uses
   only GitHub's job-scoped read token. It checks the active protected
   revision, the sender, run actors and merger by numeric ID and login,
   the single squash parent, the absence of the request in history and
   the associated PR. The merged tree must match the reviewed head tree.
   The three genuine CI jobs must have passed their tree-check step,
   with every other job skipped and zero artifacts. An unexpected actor,
   run, parent, file, or test result stops the rehearsal.

The read-only metadata check uses Git trees and Actions job results.
GitHub reported an empty `pull_requests` array and the PR head SHA
in the real PR #43 run. That field supplies neither PR association nor
the test-merge tree. The dummy proof instead combines the merged
commit's unique PR association with the exact branch, Git trees and
successful protected checkout steps. The new workflow has a three-minute
job limit and a 75-second CLI budget. The scoped check limits remain
10, 20 and 45 minutes. These bounds do not include platform queuing.
[Reference PR run](https://github.com/AuroraLilja8514/miancak/actions/runs/45244406583)

Run the rehearsal checks locally with
`python3 tools/protected-preview/test_manifest_rehearsal.py --timeout-seconds 40`.
The tests exercise the real CLI and Git history. Hosted observations
are replaced with synthetic API responses. Live delivery and the
connector's exact protected push actor are still integration checks.
Successful rehearsal evidence would inform a later production review.

## Repository-scoped App fallback

The rejected CLI credential needs its own renewal. A new GitHub App
credential is a separate authentication path. A private App installed only
on `AuroraLilja8514/miancak` can dispatch via GitHub's REST endpoint with
`Actions: write` and the platform's automatic metadata access.
`Actions: write` covers Actions operations
for the repository. A trusted dispatcher should enforce the one workflow
ID, `master` ref, reviewed commit and exact authorized request, with a
restricted dispatch interface. The installation token expires after one
hour and can be scoped to this repo and permission. An
Actions-only App still cannot bootstrap a workflow-file change.
[Workflow dispatch permissions](https://docs.github.com/en/rest/actions/workflows#create-a-workflow-dispatch-event)
[GitHub App installation tokens](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-an-installation-access-token-for-a-github-app)

For a GitHub-hosted dispatcher, the owner can put the PEM directly into a
dedicated GitHub Actions environment secret through the repository's
Settings > Environments interface on a trusted computer. Restrict that
environment to protected `master` and the key to the dedicated dispatcher
step in reviewed workflow code. GitHub's environment branch rule restricts
the ref. Branch protection governs which workflow code can run there.
Use a reviewed full commit pin of GitHub's `actions/create-github-app-token`,
an explicit repository and `permission-actions: write`. Give the resulting
token only to the fixed dispatch step. This is a supported custody route
that keeps the PEM out of chat, files in source control, logs and artifacts.
The hosted dispatcher needs its own safe trigger. The Actions environment
secret is available inside its approved job. For an independent dispatcher
outside GitHub, GitHub recommends a sign-only key
vault. No approved external signer or key-ingress connection was confirmed
for this executor. Prefer completing the manifest bootstrap before
generating a key. The offline prototype uses no App credential.
[GitHub environment secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets#creating-secrets-for-an-environment)
[GitHub App token action](https://github.com/actions/create-github-app-token)
[GitHub private-key custody](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/managing-private-keys-for-github-apps#storing-private-keys)

GitHub identifies `gpt-use` as a private App. Its selected repositories and
granted permissions remain unverified. The owner can review those fields
under the installation's GitHub configuration without retrieving a key.
[App profile](https://github.com/apps/gpt-use)
[Reviewing an installation](https://docs.github.com/en/apps/using-github-apps/reviewing-and-modifying-installed-github-apps)

The App route requires a reviewed actor change in the existing production
path. Verify the actual bot login and numeric **actor user ID** on an inert
dispatch. GitHub App and installation IDs are separate from that user ID.

- Change the owner predicates on `admit`, `build`, `authorize`, `sign-and-accept`
  and `publish` in `protected-preview.yml`, plus the caller predicates in
  `protected-preview-signer.yml` and `protected-preview-publisher.yml`.
- Give `preview_ops.workflow_revision` a production-only principal check for
  `GITHUB_ACTOR_ID`, `GITHUB_ACTOR` and `GITHUB_TRIGGERING_ACTOR`. Use the same
  principal for both REST actors in `handoff.active_run`'s production path,
  and require that run check at admission before any grant or key access.
  Preserve `approved_actor` and `owner_dispatch` for the human-only smokes
  that share those helpers. `preview_device` inherits the revision check.
  Keep `workflow_dispatch`, exact repo, ref,
  revision, workflow, attempt, run provenance and OIDC trigger binding for
  the App route.
- Keep the App identity and permissions separate from release authorization.
  Require the exact reviewed protected request for the approved preview,
  replay checks and the existing Android gate. An installation token
  authenticates a dispatcher. It cannot select a release or approve it.
  Update only the relevant production tests and proof expectations. The
  human-owned synthetic smoke and stable workflows retain their policies.

For the native path, the equivalent change is to use `push` in the
production workflow and host checks, including `handoff.PRODUCTION` and
OIDC `buildTrigger`. The merger, event and run identity checks come from
read-only admission. The checked commit, input and result attestations
continue to bind the exact protected run.
[GitHub actor and triggering actor context](https://docs.github.com/en/actions/reference/workflows-and-actions/contexts#github-context)

## Offline prototype

`tools/protected-preview/manifest_admission_prototype.py` accepts explicit
local fixture files for the request bytes and normalized host observations.
It never contacts GitHub or registers a release trigger. Its CLI tests
cover a matching fixture, a hidden workflow change next to the manifest,
non-owner identities, a forked or unmerged PR, stale CI, an artifact upload,
replayed runs and modified request pins. In production the trusted adapter
must derive those observations from GitHub and the checked-out Git trees.
Only the trusted adapter can supply runtime evidence. Workflow permission,
event delivery and actor mapping remain integration checks.

```sh
python3 tools/protected-preview/test_manifest_admission_prototype.py --timeout-seconds 35
```
