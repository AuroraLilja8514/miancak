# Wanxiang Android acceptance plan (unreleased 0.3.3)

Application minimum: Android 13 / API 33. Actual runtime scope: Android 15+ /
API 35+, using an authorized named disposable empty guest or a separately
authorized test device. No API 23 or API 33 emulation is requested. Host engine
tests, static APK inspection and instrumentation compilation do not satisfy this
plan. All cases below remain pending actual Android execution for the combined
release candidate.

Record the exact source tree/application fingerprint, APK digest/size, actual
API, ABI, image/device and page size for each run. Keep original failures and
finite watchdogs. Use the maintained guest guards and wrapper; public evidence
remains exactly the typed `summary.json` / `heartbeat.jsonl` boundary. Raw logs,
screenshots, hierarchies, preferences and learning data remain local/ephemeral.

## First deployment, waiting and resource cost

The upstream [Android deployment guide](https://github.com/amzxyz/rime-wanxiang/blob/wanxiang/docs/doc/fcitx5-android.md)
explains that dictionary compilation, grammar loading and Lua configuration can
make first deployment slow. That guide describes Fcitx5-Android, not measured
MIANCAK timings. It does not authorize extending MIANCAK's existing deadlines.

| Case | Required observations |
| --- | --- |
| Fresh installation, IME-first and application/settings-first | Keep the original ordinary enablement routes and no preference seeding. Measure monotonic time from the first Chinese-editor request to first usable candidates, then to actual committed synthetic text. Account separately for packaged-data verification/copy and engine deployment where observable. |
| Waiting feedback | The candidate view has an initialization state and word-table settings has a busy state. Verify that each is visibly shown and accessible while its own work is pending, UI remains responsive, queued/stale input is handled correctly, and ready is not displayed before real candidates. Check English/Chinese, rotation and recreation. Existing strings/code alone are not visual acceptance. |
| Cold versus warm | Keep first install/deployment, process reopen and warm resume distinct. `WordPackQualityTest` starts its engine timing after the shared-data copy; this does not measure the entire first-install user wait. Record both scopes without combining them. |
| Existing quality/resource gates | Retain the 70-case accuracy and baseline comparisons, 512 MiB process-PSS, 180-second cold engine deployment and 200 ms core-JNI P95 gates. Record failure rather than relaxing a limit because upstream warns of slow deployment. Measure model/storage/copy-fallback costs; a real model load on Linux is not a phone benchmark. |

## Interruption and recovery

Use separate fresh scenarios, bounded by the unchanged driver deadlines. An
interruption is a distinct case, not a retry to erase a failed run.

| Interruption point | Required result |
| --- | --- |
| Initial asset verification/copy or native compilation | Terminate only the owned test app at a recorded pending stage; reopen through the ordinary route. Partial data must not be presented as ready. Verify intact bundled data is repaired/validated and synthetic Chinese text is committed, or preserve the actual failure. |
| Custom dictionary/config deployment before pointer commit | Keep the previous working selection and exact imported originals. Reopen without activating a partial staged version; verify the previous candidates, patches and private policy. |
| After selection commit, before UI reports completion | Reopen the committed deployment and verify actual text, no duplicate source import, retained recovery choice and accurate status. A UI error is not proof the pointer was rolled back. |
| Learning recovery snapshot/export | Retain the original old DB byte-for-byte; a verified pristine snapshot remains after later failure. Preview cannot activate incomplete or unsupported output. Explicit discard removes only managed copies. |
| Learning recovery confirmation | Exercise changed old learning, changed selection, damaged output, failed compilation and an interrupted catalog/selection/status step. Retain originals, snapshot and any imported recovered source; verify authoritative selection, no automatic DB conversion, and clearly bounded ranking/deletion limitations. |
| Reopen/reboot/overwrite | Keep existing named-guest boot-ID/reboot checks and pinned historical upgrade baseline. Verify current preferences/layouts/catalog/patches and old/new learning remain separate through actual Android 15 overwrite and reboot. No uninstall/clear-data remedy or mocked host result substitutes for this check. |

## Input and configuration acceptance

Run the existing native, interaction, normal first-install, word-table/quality,
upgrade and reboot drivers with their original assertions. Cover normal and
protected editors after remembering a normal schema; private input must leave
learning/statistics unchanged. Exercise actual Files imports, apply/disable,
recovery, the four approved spelling selectors and their dependent schemas, and
the optional recovered-phrases confirmation. Candidate actions must reach the
receiving editor; source configuration and keycap text are insufficient.

Retain the earlier anticircle duplicate-output and settings-first reboot
failures until an exact-source Android regression run resolves them. Independently
prepared N3 work must be authorized, integrated and tested at the final head;
this branch's host results do not certify it. Physical ARM, verified 16 KiB guest
runtime and genuine formally signed two-version upgrades remain separate from
software-x86 runs and static ELF/ZIP alignment.
