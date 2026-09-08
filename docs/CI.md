# Continuous Integration

MIANCAK keeps CI intentionally small, deterministic, and useful for a one-maintainer public fork. The baseline checks are designed to protect inherited Unexpected Keyboard behavior while the Rime-specific architecture is still being built.

## When CI runs

`.github/workflows/ci.yml` runs on:

- every pull request;
- every push to `master`;
- manual `workflow_dispatch` runs.

Stale runs for the same pull request/ref are cancelled when a newer commit starts another run.

## Current required jobs

The stable baseline job names are:

```text
generated
unit
build-debug
```

These names are intended to be used in the `master` branch protection/ruleset.

### `generated`

Regenerates the checked-in files maintained by the inherited Unexpected Keyboard generators and fails if the working tree changes.

The job currently checks:

- layout list generation;
- layout warnings/check output;
- Compose key data;
- generated `method.xml`.

Generated outputs must be committed together with the source data that produced them.

### `unit`

Initializes inherited git submodules, uses Java 17, uses the repository Gradle wrapper, and runs:

```sh
./gradlew --no-daemon test
```

The repository currently pins Gradle through `gradle/wrapper/gradle-wrapper.properties`; CI does not select a different Gradle version.

After the tests, the job also requires a clean git diff so generated files cannot silently become stale.

### `build-debug`

Builds the installable debug APK with:

```sh
./gradlew --no-daemon assembleDebug
```

This includes the inherited native build already present in Unexpected Keyboard.

The resulting APK is uploaded as a short-lived GitHub Actions artifact with a seven-day retention period.

## Debug signing

Baseline CI deliberately does not consume repository signing secrets.

The inherited Gradle build can create a temporary debug keystore when one is not supplied. Therefore CI artifacts are suitable for testing, but a debug APK from a different run may not be upgrade-compatible with an already installed debug APK. Reinstalling the debug package may be necessary.

A stable CI debug signing identity may be introduced later, but it must not expose signing material to pull-request-controlled code.

## Security policy

Build/test workflows that execute pull-request code must follow these rules:

- do not use `pull_request_target`;
- use the minimum GitHub token permissions needed; the baseline workflow uses `contents: read`;
- do not expose release-signing secrets to pull requests;
- do not run untrusted PR code in a job that can access protected deployment credentials;
- pin important native/data dependency sources when they are introduced.

## Branch protection

The `master` ruleset should require a pull request and these checks:

```text
generated
unit
build-debug
```

Recommended repository-side settings:

- require a pull request before merging;
- require the three checks above;
- require conversation resolution;
- block force pushes;
- block branch deletion.

A mandatory approving review is not required for the current single-maintainer workflow.

Repository rules/settings are configured in GitHub rather than encoded in this repository because the current project tooling does not manage those administrative settings.

## Future Rime/native checks

When librime integration begins, CI should evolve without casually renaming the existing baseline jobs.

Expected additional checks include:

```text
native-build
interaction-tests
```

`native-build` should verify the pinned Android native toolchain and Rime/JNI integration. `interaction-tests` should exercise the semantic input state machine defined by `docs/INTERACTION_CONTRACT.md` independently of the UI where practical.

Do not add a multi-OS matrix merely for completeness. Android-relevant reproducibility and device/emulator coverage matter more than building the same APK on unrelated host operating systems.

## Continuous delivery

For now, continuous delivery means only producing a debug APK artifact from CI. Release signing, GitHub Releases, store publication, and F-Droid automation are intentionally deferred until MIANCAK is usable as an input method.

When release automation is introduced, it must be a separate protected path with explicit signing/provenance documentation and must not weaken pull-request security.
