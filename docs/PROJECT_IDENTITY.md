# N4 project identity and destinations

2026-11-17 source inventory for roadmap [N4 / P2](NEXT_DEVELOPMENT_PLAN.md#11-n4项目身份文档与反馈入口). This change prepares current source and text for review; API 35 APK execution remains required. N3 settings alignment, launcher animation lifecycle, translation review and store imagery are separate work.

v0.3.2 was formally released on 2026-11-16. The current 0.3.3 source is unpublished: its N1/N2 defaults are described separately in [DEFAULT_PREFERENCES.md](DEFAULT_PREFERENCES.md). The N4 source/link changes below must not be attributed to the already released APK. Dated acceptance records, hashes and failures remain historical evidence.

| Area | Source change / retained behavior |
| --- | --- |
| Project/source | `res/values/untranslated_strings.xml:launcher_repo_url` now points to `https://github.com/AuroraLilja8514/miancak`. The existing autolink remains; the TextView gains a stable ID. |
| Feedback | New `launcher_issues_url` points explicitly to `https://github.com/AuroraLilja8514/miancak/issues`, with its own autolink/ID. New `launcher_project` / `launcher_feedback` labels are reviewed in English and simplified Chinese and otherwise fall back to English. Existing enable/select/try-here IDs remain. No issue submission is performed. |
| Application/IME labels | Already generated as MIANCAK / MIANCAK (Debug) by Gradle and used by the manifest. No runtime label fix is claimed. The two metadata-source keys `app_name_release` / `app_name_debug` now use those identities in all 32 existing localized overrides. |
| Launcher instructions | Default English and simplified Chinese `launcher_description` remain the current MIANCAK instructions. Remove 29 obsolete/minimal overrides; `values-tlh` and `values-tok` already had none. Android falls back to English, while unrelated settings translations and the generic translated `launcher_sourcecode` introduction remain. This is an explicit fallback, not a claim that new translations have been verified. |
| Store source text | Replace default English and simplified Chinese `short_description` / `store_description` with offline Chinese/Rime, direct-English, candidate-scroll, symbol/editor and supported-word-table descriptions; include fork attribution and both current destinations. Remove 30 stale localized short descriptions and 29 full descriptions. |
| Generated store text | `sync_translations.py metadata` regenerates title/short/full files for all 26 existing store locales. `en-US` and `zh-CN` have reviewed text; the other 24 use English fallback. No translation-service credentials or upstream translation import is involved. Empty resource-only locales do not create new store locales. |
| Current guides | README describes compact horizontal / expanded vertical candidates with demand loading, normal-size preview tap/swipe plus the accessible list alternative, and cross-category symbol wrapping. SYMBOL_PANEL reconciles current circular browsing and R5/v4 editing. Inherited XML/key references retain technical compatibility material with current-guide/source/contribution pointers and paired-only `fn_page`. |
| Build / delivery guidance | CONTRIBUTING documents current native requirements/checks and preserves installations on signature conflict. Signing-plan/certificate pages have dated supersession pointers to RELEASING and v0.3.2. CI documents independent reboot execution and obtains ordinary version metadata from source rather than a stale number. |

The 32 localized name overrides are: `ar`, `ars`, `arz`, `bn-rBD`, `ca`, `cs`, `de`, `es`, `et`, `fa`, `fil`, `fr`, `in`, `it`, `ja`, `ko`, `lv`, `my`, `nl`, `pl`, `pt`, `ro`, `ru`, `ta`, `th`, `tlh`, `tok`, `tr`, `uk`, `vi`, `zh-rCN`, `zh-rTW`. The 29 removed launcher/full-description overrides are this list excluding `tlh`, `tok`, `zh-rCN`; removed short descriptions additionally include `tok`. Simplified Chinese is retained/revised as indicated above. The five empty resource locales (`fi`, `hi`, `hu`, `ml`, `ms`) continue to use Android fallback without gaining store listings.

## Checks and reproduction

From the repository root, using Python 3.12+ and the existing wrapper/deadline policy:

```sh
python3 sync_translations.py metadata
python3 sync_translations.py metadata --check
python3 scripts/check_project_identity.py
python3 scripts/test_project_identity.py
./gradlew --no-daemon test compileDebugAndroidTestJavaWithJavac
```

The legacy `sync` mode also generates the same metadata and reports missing translations; it does not rewrite translation files. Field limits are title 30, short description 80 and full description 4000 characters, excluding the output newline ([Play Console guidance](https://support.google.com/googleplay/android-developer/answer/9859152?hl=en)). Generation rejects empty/overlong fields and unsupported rich markup; `--check` makes no writes. The key-based identity check validates XML, edited-key placeholders, generated runtime label sources, both destinations/link attributes and exact metadata consistency. It intentionally permits Unexpected Keyboard attribution and historical references. Mutation checks reject wrong product/URL keys, stale overrides/output and lost link behavior.

`LauncherIdentityTest`, added to the existing prepared interaction guest's `native` phase, checks the shipped launcher links and inflated APK layouts for English, simplified Chinese, German/Arabic fallback and an absent locale at 240/700dp and font scales 1/1.8. TextView touch dispatch exercises real URLSpan ACTION_VIEW routing; exact scheme/host/path monitors assert outgoing targets and block browser launch. It does not send network requests, write preferences or enable/select the IME. This focused resource/touch check does not establish system-wide locale changes, physical full-screen scrolling, TalkBack usability or rotation on a guest. Follow-up API 35 English/Chinese/fallback device walks must check those visible-page behaviors. No first-install scenario is modified or reused for setup.

Future device execution uses the existing `scripts/public_test_evidence.py run --scenario interaction -- scripts/emulator_tests.sh` wrapper and the matching `stage` command, as in CI. Maintained outputs remain exactly `public-test-results/summary.json` and `heartbeat.jsonl`; no raw logs, hierarchies, images, preferences, learning data or archives become attachments. Compilation and host checks do not count as guest acceptance. See [TESTING.md](TESTING.md) and [PUBLIC_TEST_EVIDENCE.md](PUBLIC_TEST_EVIDENCE.md).

## Deliberate keeps and open work

- Keep the README fork/thanks/GPL passages, `docs/UPSTREAM.md`, LICENSE, original copyrights, third-party/dictionary provenance, historical changelogs and dated acceptance failures/hashes. No Java package, applicationId, component or data-path rename, migration, signing identity or keyboard behavior changes are part of N4.
- `.github/FUNDING.yml` and `funding.json` remain unchanged. Beneficiary policy needs a separate owner decision; no new beneficiary is selected.
- The [bounded inherited-asset review](STORE_ASSET_REVIEW.md) records actual pixel inspection of all eight store PNGs, the current-icon source comparison and a file-by-file inventory. The six screenshots depict inherited layouts/suggestions rather than the current Chinese/Rime journey; the store icon differs from the current raster launcher artwork. Missing a product name alone is not a wrong-name defect. All assets remain unchanged; fresh synthetic current-app screenshots, actual video review, adaptive-icon/launcher presentation and distribution approval remain open. Text regeneration does not make inherited images current MIANCAK evidence.
- Localized launcher/store translations beyond English and simplified Chinese remain open. Fallback preserves accurate instructions at the cost of untranslated text.
- Current APK link/layout/locale acceptance and the broader native/device matrix must run on the new source inputs. Independent backup recovery, ARM devices and a genuine two-version formal upgrade remain separate limitations; the first formal release's same-version reinstallation does not complete them.

2026-11-17 支持策略更新：当前开发源码最低 API 33，实际运行验收聚焦 API 35；早期 API 23 计划已停止，已有历史结果保留。见 [ANDROID_SUPPORT.md](ANDROID_SUPPORT.md)。
