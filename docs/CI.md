# Continuous Integration

MIANCAK CI validates the inherited frontend, offline Rime runtime and complete Android input path. All jobs run on independent GitHub-hosted runners.

## Triggers and stable checks

`.github/workflows/ci.yml` runs on pull requests, pushes to `master` and manual dispatch. A newer run cancels stale work for the same PR/ref.

Keep the baseline job names `generated`, `unit` and `build-debug` stable. Debug and minified release native builds run in parallel so real-APK tests can start after the debug artifact is verified, without waiting for the optimized release build. Both builds remain acceptance gates.

| Job | What it verifies |
| --- | --- |
| `generated` | Regenerates layout/Compose/method outputs and requires a clean diff |
| `unit` | Java 17/Gradle debug and release unit suites, then a clean diff; publishes XML and per-suite counts |
| `build-debug` | All declared native ABIs in debug/test APKs, ELF alignment, hashed offline data and provenance |
| `build-release` | All declared native ABIs in the minified unsigned release APK, package inspection and shrinking/fixture exclusions |
| `interaction-tests (23)` | Real API 23 x86_64 native, Android policy, touch/editor and process-restart tests |
| `interaction-tests (35)` | The same scenarios on real API 35 x86_64 |
| `first-install (<api>, <entry>)` | Four fresh-image APK jobs: API 23/35 and settings-first/IME-first, Android UI enablement/selection and process reopen |

The emulator action uses a supported SDK platform for provisioning and selects the guest system image separately. The script asserts the guest API before testing, so platform provisioning cannot silently change the tested Android version.

## Build and artifacts

The build jobs initialize inherited submodules and prepare sources/data pinned by `native/sources.lock.json`. Source and native-object caches improve repeat runs; preparation rejects modified Git source caches and verifies downloaded archive hashes.

The key commands are:

```sh
python3 scripts/prepare_rime.py
./gradlew --no-daemon test
./gradlew --no-daemon assembleDebug assembleDebugAndroidTest assembleRelease
python3 scripts/check_native_apk.py build/outputs/apk/debug/*.apk
python3 scripts/check_native_apk.py build/outputs/apk/release/*.apk --release
```

Actual APK inspection verifies every declared ABI, 16 KiB ELF load-segment alignment, offline asset hashes and dependency provenance. Release inspection also checks that JNI class names survive shrinking and the debug editor fixture is absent.

Artifacts are retained for seven days:

- `miancak-debug-<sha>`: installable debug APK and its instrumentation APK.
- `miancak-unsigned-release-<sha>`: minified release APK requiring a signing identity before installation.
- `unit-results-<sha>`: debug/release JUnit XML reports.
- `instrumented-results-api-<api>-<sha>`: native/touch/restart logs, screenshots, UI dump, logcat and scoped measurements.
- `first-install-api-<api>-<entry>-<sha>`: APK hash, device properties, installation/input/reopen assertions, UI hierarchies and screenshots.

PR artifact names contain the synthetic merge SHA. The run metadata identifies the source branch head. See [TESTING.md](TESTING.md) for scenario coverage and measurement limits.

## Debug and release signing

PR CI consumes no signing secrets. If no debug keystore is supplied, the inherited build creates a temporary one. Debug artifacts from different runs can therefore have different signing identities; reinstalling may be necessary.

Without `RELEASE_KEYSTORE`, release builds produce an unsigned APK. Supplying the existing release keystore/password/alias environment variables enables signing through the explicit release configuration. No signing key is stored in the repository or exposed to PR code.

## Repository rules

Recommended `master` rules require a PR, all applicable checks above and conversation resolution, and block force pushes/deletion. Input changes must pass both builds, both interaction jobs and all four first-install routes. A mandatory approving review is not required for this single-maintainer workflow. Rules/settings are managed in GitHub; this documentation does not claim to configure them.

Build/test workflows must use `contents: read`, avoid `pull_request_target`, and keep protected deployment/signing credentials out of PR execution. New native/data dependencies must have pinned sources and provenance.

## Delivery boundary

CI produces debug and unsigned release artifacts. Protected release signing, GitHub Releases, store publication and F-Droid automation remain separate release work. A passing emulator matrix does not imply physical-device or store-release validation.

## Final APK acceptance jobs

`build-upgrade-baseline` builds the pinned old application at `c939309635ee80c058d59ca712eae4ef1b3acb14` for x86_64. `upgrade-tests (23/35)` install that actual APK, exercise Chinese input, seed a retiring layout selection, replace it with the new APK without clearing data, and reboot the guest. Test copies of the old/new/test APKs are re-signed with one ephemeral local test identity; original delivery APKs and signing workflows are unchanged. Logs record both original and test-copy hashes. The test verifies configuration/data-directory preservation and Chinese input; it does not claim a production signing upgrade path.

API 35 interaction tests use system font scale 1.3. The API 35 settings-first fresh-install case also uses Android application locale `zh-CN`, without preselecting a layout. Android DocumentsUI is exercised for layout export/import, including cancelled edits, saved resets and disabled Fn-target recovery. All applicable jobs remain acceptance gates.
