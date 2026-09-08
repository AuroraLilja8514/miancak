# Contributing to MIANCAK

MIANCAK is primarily a personal project, but it is developed in the open and should remain understandable, reproducible, and legally clean.

This document defines how project changes should be made. For detailed branch/PR conventions, see [docs/DEVELOPMENT_WORKFLOW.md](docs/DEVELOPMENT_WORKFLOW.md). For the exact CI contract, see [docs/CI.md](docs/CI.md).

## Before changing code

Read:

- [README.md](README.md)
- [docs/INTERACTION_CONTRACT.md](docs/INTERACTION_CONTRACT.md)
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- [docs/DEVELOPMENT_WORKFLOW.md](docs/DEVELOPMENT_WORKFLOW.md)
- [docs/CI.md](docs/CI.md)
- [docs/UPSTREAM.md](docs/UPSTREAM.md)

The interaction contract is normative. If an implementation appears easier only by violating it, the design must be discussed and documented before code is changed.

## Development model

Project work should normally follow:

```text
Issue -> short-lived branch -> Draft PR -> CI/review -> squash merge
```

Use the repository Issue/PR templates rather than keeping scope and acceptance criteria only in chat history.

Do not use `master` as a scratch branch. Keep PRs narrow. Avoid bundling architectural rewrites, formatting changes, dependency upgrades, and feature work into one change unless they are inseparable.

## Commit style

Use concise Conventional-Commit-style messages where practical, for example:

```text
feat(input): add engine key semantics
feat(rime): add native session bridge
fix(input): preserve literal punctuation during composition
test(input): cover shift latch in Chinese mode
ci(android): build native debug APK
docs(interaction): define language switching invariants
```

## Building the inherited Android project

Until MIANCAK's Rime-native toolchain is introduced, the project inherits Unexpected Keyboard's existing Android build requirements:

- OpenJDK 17
- Android SDK / platform 36
- Python 3 for generated-file maintenance tasks

Initialize submodules:

```sh
git submodule update --init
```

Use the repository Gradle wrapper rather than installing/selecting another Gradle version:

```sh
./gradlew test
./gradlew assembleDebug
```

The wrapper currently pins the project Gradle version in `gradle/wrapper/gradle-wrapper.properties`.

## Installing for local testing

With Android debugging enabled and a device connected:

```sh
./gradlew installDebug
```

If Android reports a signature mismatch for an existing debug installation, uninstall the old debug package and install again. Enabling an input method is an explicit Android user action and may need to be repeated after uninstalling.

CI debug APKs intentionally do not rely on a stable signing secret yet, so APKs from different CI runs may also require reinstalling rather than upgrading in place.

## CI before merge

The baseline required CI jobs are:

```text
generated
unit
build-debug
```

`generated` verifies checked-in generated data, `unit` runs the inherited unit tests with the Gradle wrapper, and `build-debug` produces an installable debug APK artifact.

Do not merge while a required check is failing. Do not bypass a failure by weakening the workflow or deleting a test unless the underlying project contract is deliberately being changed.

See [docs/CI.md](docs/CI.md) for the security policy and future Rime/native checks.

## Interaction changes

Any change to keyboard behavior should answer all of these questions in its PR:

1. Does it preserve one-finger operation?
2. Does it preserve stable physical key positions across Chinese/English modes?
3. Does it distinguish engine input from exact literal output?
4. Does it avoid hidden punctuation/context heuristics?
5. What happens while a Rime composition is active?
6. How does it interact with Shift latch/lock?
7. Is the behavior covered by state-machine tests?

## Layout changes

Unexpected Keyboard layouts are XML-based and generated metadata may need regeneration/checking.

When touching inherited layout infrastructure, preserve upstream conventions unless MIANCAK has documented a reason to diverge. Avoid unnecessary changes that make future upstream synchronization harder.

## Generated files

Do not hand-edit generated outputs when a generator is the source of truth.

If a task changes source data used by an inherited generator, run the corresponding generator/check and include the resulting generated changes in the same PR. CI will fail if the checked-in generated outputs are stale.

## Dependencies and licenses

Do not add a binary, native library, dictionary, schema, model, or other third-party artifact without recording:

- source/upstream project;
- exact version or commit when practical;
- license;
- whether it is modified;
- how it is built or obtained.

Update [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) when a dependency is actually introduced.

Do not remove or obscure copyright/license notices inherited from Unexpected Keyboard.

## Privacy and network access

Normal typing and composition must work offline.

Adding network permission or a network dependency to the input path requires an explicit design discussion and is outside the initial project scope.

## Large refactors

Avoid broad rewrites that do not directly enable an agreed feature. In particular, do not perform a Java-to-Kotlin migration or rewrite Unexpected Keyboard's gesture engine as incidental cleanup.

Prefer small adaptation layers around proven upstream behavior.

## AI-assisted development

AI agents may author or review code in this repository. Their output is treated like any other contribution: it must be reviewable, testable, license-compatible, and understandable from the repository itself.

Prompts/chat history are not a substitute for committed specifications. Important behavioral or architectural decisions belong in `docs/` and task acceptance criteria belong in Issues/PRs.
