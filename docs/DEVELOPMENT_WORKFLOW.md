# Development Workflow

MIANCAK is developed as a public fork with a small, reviewable history. The workflow is optimized for one maintainer plus AI-assisted contributors without turning the repository into an unstructured scratchpad.

The normal path is:

```text
Issue -> short-lived branch -> Draft PR -> CI/review -> squash merge
```

## `master`

`master` is the integration branch and should stay buildable.

Do not use `master` as a scratch branch and do not intentionally bypass the PR workflow for normal development. Force pushes and branch deletion should be blocked by the GitHub ruleset.

## Issues

Create an Issue for meaningful project work when it helps establish a stable specification. The repository provides a `Project task` Issue template for this purpose.

Issues are especially useful for:

- work delegated to an AI agent;
- changes spanning more than one commit;
- behavior with testable acceptance criteria;
- work that other branches depend on.

An Issue should state:

- goal;
- scope;
- non-goals;
- acceptance criteria;
- interaction impact;
- subsystem/file ownership;
- dependency/license impact;
- required verification.

Chat prompts may reference the Issue, but should not be the only place requirements exist.

## Branches

Use short-lived branches with a category prefix, for example:

```text
docs/interaction-notes
feat/semantic-input-controller
feat/rime-native-runtime
feat/rime-candidate-strip
fix/input-literal-ordering
ci/native-build
chore/dependency-metadata
```

Do not use personal long-lived branches as hidden integration branches.

Parallel branches should have explicit ownership boundaries. Shared interfaces are changed deliberately, not independently by multiple agents.

## Pull Requests

Open a Draft PR early for non-trivial work. The repository PR template is part of the expected workflow.

A PR should describe:

- what changed and why;
- which Issue/specification it implements;
- user-visible interaction changes;
- tests/device checks performed;
- dependency/license changes;
- upstream-sync impact;
- known limitations or deferred work.

Do not hide architecture changes inside an apparently small implementation PR.

Mark a PR ready for review only when its scope is coherent and it is reasonable to expect the baseline CI to pass. A Draft PR is still expected to run CI.

## Merge policy

Prefer squash merge for narrowly scoped feature, documentation, CI, and fix branches unless preserving individual commits has clear value.

Do not merge while required checks are failing.

Resolve review discussions or explicitly document why a concern is deferred.

After merge, the Issue should close automatically when the PR uses `Closes #N` where appropriate.

## Commit messages

Use Conventional-Commit-style prefixes where practical:

```text
feat(...)
fix(...)
test(...)
docs(...)
ci(...)
refactor(...)
chore(...)
```

The scope should identify the subsystem rather than the author/agent.

Examples:

```text
feat(input): add engine key semantics
feat(rime): add native session bridge
fix(input): preserve literal punctuation during composition
test(input): cover shift latch in Chinese mode
ci(android): build native debug APK
docs(interaction): define language switching invariants
```

## CI expectations

MIANCAK owns its CI workflow. The current baseline required jobs are:

```text
generated
unit
build-debug
```

Their behavior and security policy are documented in [CI.md](CI.md).

The job names are intentionally stable because GitHub branch protection/rulesets may depend on them. Renaming one is a repository-governance change, not cosmetic cleanup.

When Rime/native work begins, add checks such as:

```text
native-build
interaction-tests
```

only when there is real code for them to validate.

PR workflows must not receive release-signing secrets. Do not use `pull_request_target` for build/test workflows that execute PR-controlled code.

## Agent-assisted development

Parallel AI agents are allowed, but they must work against explicit ownership boundaries.

A practical decomposition after the architecture contract is stable is:

```text
native/Rime runtime
semantic input state machine
candidate UI
        |
        +-- integration after the above stabilize
```

Agents should not independently redesign shared interfaces after parallel work starts.

Each agent must:

- read the relevant committed docs before coding;
- read the Issue/acceptance criteria for its task;
- use its own branch;
- avoid direct pushes to `master`;
- keep changes within its assigned subsystem;
- open a Draft PR;
- report tests and unresolved decisions;
- update dependency/license provenance when applicable;
- avoid weakening invariants merely to make integration easier.

## Interaction changes require tests

The following behaviors should eventually be covered explicitly by state-machine or integration tests:

- Chinese vs English routing;
- Shift latch/lock interaction;
- literal punctuation during composition;
- literal digits during composition;
- backspace while composing vs idle;
- space while composing vs idle;
- candidate selection;
- language switching with and without active composition.

The interaction contract in [INTERACTION_CONTRACT.md](INTERACTION_CONTRACT.md) is normative. A PR that changes those semantics must identify the affected invariant and update tests/documentation deliberately.

## Keep upstream mergeability where useful

Changes to inherited Unexpected Keyboard code should be as local as practical. Do not reformat or rename broad areas of upstream code just to match a new local style.

Small adaptation layers are preferred when they preserve the ability to understand upstream diffs.

See [UPSTREAM.md](UPSTREAM.md).

## Dependencies

A dependency change must record source, version/commit where practical, license, modification status, and how it is built or obtained. Update `THIRD_PARTY_NOTICES.md` when the dependency is actually introduced.

Do not silently vendor binaries, schemas, dictionaries, models, or generated native artifacts.

## Release work

For now, CI delivers short-lived debug APK artifacts only.

Release signing, GitHub Releases, store publishing, and F-Droid-specific automation are intentionally deferred until the application is usable. Future release automation must use protected secrets/environments and remain isolated from pull-request-controlled code.
