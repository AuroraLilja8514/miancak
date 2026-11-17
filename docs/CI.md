# Continuous Integration

MIANCAK CI validates the inherited frontend, offline Rime runtime and complete Android input path. All jobs run on independent GitHub-hosted runners.

## Triggers and stable checks

`.github/workflows/ci.yml` runs on pull requests, pushes to `master` and manual dispatch. A newer run cancels stale work for the same PR/ref.

Keep the baseline job names `generated`, `unit` and `build-debug` stable. Debug and minified release native builds run in parallel so real-APK tests can start after the debug artifact is verified, without waiting for the optimized release build. Both builds remain acceptance gates.

| Job | What it verifies |
| --- | --- |
| `generated` | Regenerates layout/Compose/method outputs, checks identity/source keys, store metadata and release contracts/embedded helper generation, and requires a clean diff |
| `unit` | Java 17/Gradle debug and release unit suites and Android test-driver compilation, then a clean diff; publishes XML and per-suite counts |
| `build-debug` | All declared native ABIs in debug/test APKs, ELF alignment, hashed offline data and provenance |
| `build-release` | All declared native ABIs in the minified unsigned release APK, package inspection and shrinking/fixture exclusions |
| `interaction-tests (35)` | Real API 35 x86_64 native, Android policy, touch/editor and process-restart tests |
| `first-install (<api>, <entry>)` | Two fresh-image API 35 APK jobs: settings-first/IME-first, Android UI enablement/selection and process reopen |
| `build-upgrade-baseline` | Builds the pinned actual 0.3.0 source for replacement tests |
| `upgrade-tests (35)` | Real old APK input/learning, overwrite installation, configuration migration and guest reboot |
| `word-table-tests (35)` | Native recovery plus actual Files import, base/addon selection, offline fallback, learning, APK replacement and reboot |
| `word-pack-quality (35)` | Same schema, isolated users and fixed corpus for old/full/core data; candidate/commit and resource thresholds |
| `emulator-reboot (35)` | Independent empty guest, three actual adb reboots with changed boot IDs; runs independently of APK jobs on other hosts |

The public-base matrix has 12 expanded checks, derived and compared against the required release gate names by `tools/release/test_release.py`. Application/test minSdk is 33; runtime jobs target API 35 only. Additional jobs integrated later must also enter the exact gate set; a stale count is not a waiver. Emulator version and GPU settings are pinned for API 35; see [EMULATOR_ENVIRONMENT.md](EMULATOR_ENVIRONMENT.md). Every maintained script has a finite configurable deadline, and individual ADB/instrumentation operations have shorter limits; see [SCRIPT_TIMEOUTS.md](SCRIPT_TIMEOUTS.md).

The `generated` job runs the release host contracts without signing inputs or SDK fixtures. They check the expanded workflow names against the reviewed gate set and verify generated inline helpers. During actual release preparation and provenance rechecks, the helper independently requires exactly that job-name set from the selected CI run and a successful latest result for every gate. Unknown jobs are rejected even when successful, so adding a matrix entry requires updating the reviewed gate set in the same change.

The emulator action uses a supported SDK platform for provisioning and selects the guest system image separately. The script asserts the guest API before testing, so platform provisioning cannot silently change the tested Android version.

## Build and artifacts

The build jobs initialize inherited submodules and prepare sources/data pinned by `native/sources.lock.json`. Source and native-object caches improve repeat runs; preparation rejects modified Git source caches and verifies downloaded archive hashes.

The key commands are:

```sh
python3 scripts/prepare_rime.py
./gradlew --no-daemon test compileDebugAndroidTestJavaWithJavac
./gradlew --no-daemon assembleDebug assembleDebugAndroidTest assembleRelease
python3 scripts/check_native_apk.py build/outputs/apk/debug/*.apk
python3 scripts/check_native_apk.py build/outputs/apk/release/*.apk --release
```

Actual APK inspection verifies every declared ABI, 16 KiB ELF load-segment alignment, offline asset hashes and dependency provenance. Release inspection also checks that JNI class names survive shrinking and the debug editor fixture is absent.

The unit job also compiles the Android test driver against the public SDK so API/type errors fail before emulator provisioning. The emulator jobs still execute that driver against the installed APKs.

Artifacts are retained for seven days:

- `miancak-debug-<sha>`: installable debug APK and its instrumentation APK.
- `miancak-unsigned-release-<sha>`: minified release APK requiring a signing identity before installation.
- `unit-results-<sha>`: debug/release JUnit XML reports.
- `instrumented-results-api-<api>-<sha>`: typed native/touch/restart outcomes, counts/timing, input hashes, numeric metrics and bounded heartbeat fields.
- `first-install-api-<api>-<entry>-<sha>`: typed installation/input/reopen outcomes, input hashes and bounded heartbeat fields.

All device families, including release acceptance, upload only reconstructed
`public-test-results/summary.json` and `heartbeat.jsonl` after successful staging.
Driver stdout/stderr is captured locally; the job console contains fixed status
tokens. Raw logs, hierarchies, screenshots and private maps remain local and are
excluded from default uploads. See [PUBLIC_TEST_EVIDENCE.md](PUBLIC_TEST_EVIDENCE.md)
for the allowlist, target guards, failure semantics and interpretation limits.

PR artifact names contain the synthetic merge SHA. The run metadata identifies the source branch head. See [TESTING.md](TESTING.md) for scenario coverage and measurement limits.

## Debug and release signing

PR CI consumes no signing secrets. If no debug keystore is supplied, the inherited build creates a temporary one. Debug artifacts from different runs can therefore have different signing identities. A signature conflict requires checking the old identity and data-export/migration limits; do not treat uninstalling or clearing data as a lossless upgrade.

Without `RELEASE_KEYSTORE`, release builds produce an unsigned APK. Supplying the existing release keystore/password/alias environment variables enables signing through the explicit release configuration. No private signing key is stored in the repository or exposed to PR code.

The [registered certificate](signing/README.md) signs formal releases through the separate [release workflow](RELEASING.md); v0.3.2 was released on 2026-11-16. The independent [credential proof](SIGNING_CREDENTIAL_VALIDATION.md) is a separate key-availability check. Ordinary CI remains secret-free, and Gradle signing variables are not a recommendation to execute application build code with the production key accessible. The first formal release validated same-version reinstallation; independent backup recovery, ARM-device execution and a genuine two-version formal upgrade remain separate pending work.

## Repository rules

Recommended `master` rules require a PR, all applicable checks above and conversation resolution, and block force pushes/deletion. Input changes must pass both builds, the API 35 interaction job and both fresh-install routes. A mandatory approving review is not required for this single-maintainer workflow. Rules/settings are managed in GitHub; this documentation does not claim to configure them.

Build/test workflows must use `contents: read`, avoid `pull_request_target`, and keep protected deployment/signing credentials out of PR execution. New native/data dependencies must have pinned sources and provenance.

## Delivery boundary

Ordinary CI produces debug and unsigned release artifacts. The separate [formal release workflow](RELEASING.md) checks exact inputs, signs and validates the release APK before GitHub Releases publication. Store publication and F-Droid automation remain separately scoped work. A passing emulator matrix does not imply physical-device or store-release validation.

## Word-table acceptance

`word-table-tests (35)` are mandatory; their initial addition brought the historical matrix to 15 checks, before quality and independent reboot checks were added. `build-debug` also builds a feature-enabled older-version fixture before the ordinary application (fixture overrides and artifact selection are defined in `.github/workflows/ci.yml`; the ordinary version comes from `build.gradle.kts`), using the same temporary debug identity. The current-source fixture inherits minSdk 33 and retains its version-code override. The fixture is uploaded separately so existing jobs and delivery cannot accidentally choose it as the production artifact. It is not the historical released 0.2.0 source.

The word jobs run real native catalog/selection/recovery tests, import complete existing dictionaries through DocumentsUI, use the shipped keyboard in real editors, overwrite-install the ordinary application selected by the workflow and reboot. The host fingerprints actual learning-database files while the app is stopped, before instrumentation can write them, and compares them across replacement and reboot. Historical upgrade gates independently verify migration/default-IME retention. Fixture source hashes and licenses are verified by `scripts/check_word_table_fixtures.py`.

## Final APK acceptance jobs

`build-upgrade-baseline` builds the pinned old application at `ca6c0b309306767e434ce922e4a3409c7093e8b9` for x86_64, retaining its original manifest floor. Raising the new floor must not alter or reject this old input. `upgrade-tests (35)` install that actual APK, exercise Chinese input, seed a retiring layout selection, replace it with the new APK without clearing data, and reboot the guest. Test copies of the old/new/test APKs are re-signed with one ephemeral local test identity; original delivery APKs and signing workflows are unchanged. Logs record both original and test-copy hashes. The test verifies configuration/data-directory preservation and Chinese input; it does not claim a production signing upgrade path.

API 35 interaction tests use system font scale 1.3. The API 35 settings-first fresh-install case also uses Android application locale `zh-CN`, without preselecting a layout. Android DocumentsUI is exercised for layout export/import, including cancelled edits, saved resets and paired Fn-variant and phonetic-entry recovery. All applicable jobs remain acceptance gates.

## Layout repair and formal word-pack checks

The earlier repair branch added quality and independent reboot checks to the historical 18-job API 23/35 matrix. Current `word-pack-quality (35)` and `emulator-reboot (35)` gates retain those assertions. The quality jobs install the same APK/test artifacts into separate unused offline guests and compare the pinned old vocabulary, full Ice data and chosen Ice core with fresh isolated users. Reports retain actual candidates/commits locally; public summaries project only numeric cold deployment, JNI latency, observed process PSS and footprint. The core is now the explicit production default; the full pack remains a comparison asset and the old pack an offline fallback. A failed quality threshold remains a failed gate independently of UI results; passing a comparison cannot silently change the default.

Interaction evidence now includes real F1–F12 and navigation events, Fn literal Tab/NBSP and editing actions, categorized symbols, center plus eight directions, explicit Shift and paired edits, existing gesture compatibility, Files round trips and process restart. Runtime slot inventories cover normal/Shift outputs and Fn reachability under six number-row/numpad/order configurations; they complement actual touches and do not claim every slot was physically touched. Shared controls are read-only, Compose is unchanged, and advanced gesture editing H1 is deferred.

Guest reboots remain mandatory. Emulator host graphics crashes are recorded as environment failures, never converted into app passes; any specific-job retry is identified in the execution record.
