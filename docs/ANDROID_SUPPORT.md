# Android support policy

The unreleased 0.3.3 development source requires Android 13 / API 33. Compile and target SDK remain 36. Runtime acceptance currently targets Android 15 / API 35, with newer Android profiles added only through an explicit validated configuration. No API 23 or API 33 guest runs are planned. An installation floor does not claim API 33 runtime coverage.

`build.gradle.kts` supplies the shared application/instrumentation minimum and the active CMake `ANDROID_PLATFORM`. Keep arm64-v8a, armeabi-v7a and x86_64, minified release packaging, ELF load-segment and ZIP 16 KiB alignment checks. Static alignment does not establish execution on a verified 16 KiB-page guest. The retained upstream `vendor/Android.mk` floor belongs to an inactive build path and is not rewritten.

## Current runtime gates

The public-base CI matrix retains API 35 interaction, both fresh-install entry orders, word-table, word-pack quality, historical APK overwrite-upgrade and independent reboot jobs. The reboot driver requires its exact named disposable empty guest, actual API 35/qemu properties and pinned Emulator 37.2.12 / 16428233. Each cycle still requires a changed kernel boot ID plus completion within the original finite deadline. App scenarios retain their own fresh named guards, assertions and timeouts. Reboot diagnostics on one host cannot certify an app on another.

Release/rehearsal acceptance likewise targets fresh named API 35 guests. All maintained device outputs still pass through the existing wrapper and exact typed `summary.json` / `heartbeat.jsonl` staging boundary. No raw evidence is made distributable by this policy change.

The release guard compares the complete expanded CI job-name set against its required gates. The current public-base set has 12 entries. The separate unpublished N3 settings-alignment jobs are not integrated here; final integration must add every surviving API 35 English/Chinese job to the required gate set and run exact-head contract checks. A count or a previous source result cannot waive a missing gate.

## New outputs and previous APKs

New formal-package outputs must declare minSdk 33. An older valid formal release remains an immutable upgrade input with its truthful manifest (including v0.3.2 / versionCode 5 / minSdk 21). Previous-input validation accepts historical floors from 21 through 33 while retaining package, non-debuggable, ABI, version, certificate, digest and strictly increasing version-code checks. It does not rebuild or relabel the previous APK. Actual two-version overwrite and learning/preferences retention on API 35 remain runtime gates.

The pinned historical source in `build-upgrade-baseline` and the current-source version-code-2 feature-enabled word-table fixture have different roles. The historical source/manifest stay pinned; the current-source fixture inherits minSdk 33. Neither is substituted for the previous formal release.

Signing explicitly keeps v1/v2/v3 enabled and v4 disabled. New output signing uses its declared minimum. Verification performs a separate all-schemes audit from API 21 and a supported-range check from the actual manifest minimum. The lower audit floor is not an installation-support claim: modern-only apksig verification can skip v1/v2 after finding v3. See [ApkVerifier source](https://android.googlesource.com/platform/tools/apksig/+/refs/heads/main/src/main/java/com/android/apksig/ApkVerifier.java#283). The credential-availability probe and formal certificate identity do not change.

## Engineering review checklist

| Edge | Implementation / evidence boundary |
| --- | --- |
| Shared app/test floor and active native platform | Gradle minimum 33; inspect all built manifests and CMake ABI configurations on the new exact source. |
| Active CI and release generation | API 35 matrices/pin/renderer, strict reboot properties and generated inline helpers updated together; host contracts must pass. |
| Evidence API/directory allowlists | Current API 35 target/staging and settings-alignment directory pattern; retain privacy/refusal coverage and exact two-file boundary. |
| New versus previous release role | MinSdk 33 current output, old truthful floor accepted only as previous input; official Build Tools disposable fixtures exercise both roles and all schemes. Host fixtures do not establish runtime upgrade. |
| Upgrade inputs and native ABIs | Keep pinned historical baseline and separate feature fixture, all three ABIs and both 16 KiB checks. |
| Current guidance and metadata | Current instructions and English/Chinese store source keys updated, metadata regenerated. Historical records, upstream attribution, assets and funding preserved. |
| Final N3 integration | Open: integrate the independent API 35 locale jobs and matching required gates, then rerun exact combined-head checks. |
| Actual device acceptance | Open until recorded on exact new APK bytes: focused launcher, full interaction, both unused-install routes, overwrite/reboot, locale/font/rotation/accessibility and ARM/physical/16 KiB-page behavior. |

Dated API 23 evidence and v0.3.2 support claims describe their original inputs. They are preserved, including failures, rather than converted into results for this development source. Store screenshots/video review, translations, independent signing-backup recovery and genuine two-version formal upgrade remain separately tracked work. This document does not announce a release.
