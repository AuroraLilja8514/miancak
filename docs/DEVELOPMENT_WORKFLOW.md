# Development Workflow

MIANCAK is developed as a public fork with a small, reviewable history. The workflow is optimized for one maintainer plus AI-assisted contributors without turning the repository into an unstructured scratchpad.

## Branches

`master` is the integration branch and should stay buildable.

Normal work uses short-lived branches such as:

```text
docs/bootstrap-project
feat/semantic-input-controller
feat/rime-native-runtime
feat/rime-candidate-strip
fix/input-literal-ordering
ci/native-build
```

Do not use personal long-lived development branches as hidden integration branches.

## Issues

Create an Issue for meaningful project work when it helps establish a stable specification.

Issues are especially useful for:

- work delegated to an AI agent;
- changes spanning more than one commit;
- behavior with testable acceptance criteria;
- work that other branches depend on.

An Issue should state scope, non-goals, and acceptance criteria. Chat prompts may reference the Issue, but should not be the only place the requirements exist.

## Pull Requests

Open a Draft PR early for non-trivial work.

A PR should describe:

- what changed;
- why it changed;
- which Issue/specification it implements;
- user-visible interaction changes;
- tests performed;
- dependency/license changes;
- known limitations.

Do not hide architecture changes inside an apparently small implementation PR.

## Merge policy

Prefer squash merge for narrowly scoped feature/documentation branches unless preserving individual commits has clear value.

Do not merge while required checks are failing.

Resolve review discussions or explicitly document why a concern is deferred.

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

## CI expectations

The inherited Unexpected Keyboard checks remain the baseline until MIANCAK adds its own jobs.

As native/Rime work begins, the intended required checks are:

```text
generated
unit
native-build
interaction-tests
```

CI should be reproducible on a documented Android/Java/NDK/CMake toolchain.

PR workflows must not receive release-signing secrets. Avoid `pull_request_target` for build/test workflows that execute PR-controlled code.

## Agent-assisted development

Parallel AI agents are allowed, but they must work against explicit ownership boundaries.

A practical decomposition is:

```text
bootstrap contract/interfaces
       |
       +-- native/Rime runtime
       +-- semantic input state machine
       +-- candidate UI
       |
       +-- integration/CI after the above stabilize
```

Agents should not independently redesign shared interfaces after parallel work starts.

Each agent must:

- read the relevant committed docs before coding;
- use its own branch;
- avoid direct pushes to `master`;
- keep changes within its assigned subsystem;
- open a Draft PR;
- report tests and unresolved decisions;
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

## Keep upstream mergeability where useful

Changes to inherited Unexpected Keyboard code should be as local as practical. Do not reformat or rename broad areas of upstream code just to match a new local style.

Small adaptation layers are preferred when they preserve the ability to understand upstream diffs.

See [UPSTREAM.md](UPSTREAM.md).

## Release work

Release signing, store publishing, and F-Droid-specific automation are intentionally deferred until the application is usable.

When release automation is added, signing material must live in protected GitHub secrets/environments and never in the repository.
