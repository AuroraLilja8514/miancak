# Dictionary Roadmap

Accepted on 2026-10-23; status corrected on 2026-10-25. **Cross-language common-format/cdict work remains deferred.** Existing compatible Rime word-table support (#22) was delivered in 0.3.0 through [PR #30](https://github.com/AuroraLilja8514/miancak/pull/30), with the explicit-Pinyin profile in [RIME_WORD_TABLES.md](RIME_WORD_TABLES.md). This is not universal Rime compatibility or acceptance of the bundled vocabulary's everyday Chinese quality. The new formal Chinese vocabulary work is planned in [LAYOUT_REPAIR_PLAN.md](LAYOUT_REPAIR_PLAN.md); 0.3.1 adopts the measured Rime Ice 8105 + base core with an offline legacy fallback; final acceptance is recorded in FN_REPAIR_PROGRESS.md. Track common-source/cdict migration in [issue #18](https://github.com/AuroraLilja8514/miancak/issues/18), and Chinese usability in [issue #20](https://github.com/AuroraLilja8514/miancak/issues/20).

## Accepted direction

Maintain language dictionaries in one common Rime-compatible `.dict.yaml` source format. Keep language/schema configuration, package metadata and provenance separate. Rime's compiled artifacts and any transitional cdict binary artifacts are generated outputs; sharing source data does not require every language to use the same runtime backend.

Investigate converting the source word lists used by cdict. The converter/data pipeline may later become an independent project with a small, versioned interface consumed by MIANCAK. Decide its repository boundary after a useful pilot exists. No separate repository or runtime migration is created by this plan.

## Current baseline and source boundaries

- 0.3.1 packages the pinned Rime Ice 8105 + base core in `native/word-pack.lock.json`, retaining the 0.3.0 `rime/rime-pinyin-simp` data for offline fallback. Chinese composition uses librime; English uses the direct path.
- Unexpected Keyboard's inherited dictionary pipeline is [Helium314/aosp-dictionaries](https://codeberg.org/Helium314/aosp-dictionaries) -> [Julow/Unexpected-Keyboard-dictionaries](https://github.com/Julow/Unexpected-Keyboard-dictionaries) -> [cdict](https://github.com/Julow/cdict) binary dictionaries. Its catalog/downloader is distinct from keyboard layouts.
- The inherited catalog is not evidence that multilingual completion/correction is implemented in MIANCAK's new input path. A layout, a word list and an input algorithm are separate deliverables.
- A compiled cdict `.dict` file is not a Rime dictionary source. Start conversion from traceable source word lists, not a renamed binary.
- Preserve original upstream inputs and notices. The common-format requirement applies to maintained dictionary data; upstream originals remain archived or reproducibly fetchable as provenance inputs.

## R: existing Rime word-table support

The initial supported profile was implemented in 0.3.0 (#22/#30), separately from installation of a third-party input scheme. Keep MIANCAK's own schema, gestures, candidate interaction and punctuation/digit behavior while changing vocabulary data. Full existing source tables with unsupported codes or uncoded rows require additional profile/data-pipeline work; the completed import gate does not claim that work is done.

Implemented acceptance scope (actual results in PR #30):

1. Define the supported Rime dictionary-source profile and handling of referenced word tables. Validate names, fields, encoding and input-code compatibility; explain unsupported data clearly.
2. Import existing word tables without rebuilding the APK. Allow replacement/selection of a base vocabulary, enable/disable supplementary tables, remove imported data and restore the bundled vocabulary.
3. Compile/deploy changes outside ordinary key handling, preserve personal learning/settings, and retain the previous working selection when import or deployment fails.
4. Verify two distinct base vocabularies and an add-on combination with actual engine candidates and committed editor text, then cover restart/upgrade preservation and invalid/missing-dependency cases.

Prepared MediaWiki-derived tables and vocabulary from other Rime projects are source examples. Wanxiang is optional, and its complete scheme, Lua functions and models are not requirements. Manual phrase editing and data crawling/preparation are also separate tasks. A supported data profile must not be described as universal compatibility with every encoding used by Rime schemes.

#22 did not wait for the cdict converter/new project in #18 or for reproduction of an unrelated input defect in #20. Future profile expansion and formal-vocabulary quality must use their own recorded scope and acceptance; current implementation details are in [RIME_WORD_TABLES.md](RIME_WORD_TABLES.md).

## D1: common source-format contract

Define a versioned profile for UTF-8 Rime dictionary sources, covering entry text, input codes, weights, duplicate handling, ordering, Unicode normalization, case and language-specific characters. Specify the supported Rime components and schema settings needed to use the files.

Define companion metadata for language/region, source URL/revision/hash, source license/notices, modification status, converter version, schema dependencies and package version/hash. A language's word-list license must be checked separately from the engine license. Add actual dependencies/data to `THIRD_PARTY_NOTICES.md` when introduced.

Identify metadata that does not map directly into Rime fields. Specify sidecar preservation, a documented mapping or an explicit unsupported case; never silently claim a lossless conversion.

Acceptance: a reviewed format specification and per-language source inventory sufficient for two implementations to consume the same maintained data. No new app suggestion behavior is implied.

## D2: English conversion and runtime pilot

Choose one English regional word list with a pinned source revision and clear provenance. Build a deterministic converter that emits the common Rime-compatible source and the declared metadata. Keep schema-specific behavior separate from vocabulary conversion.

Compare the converted vocabulary and useful completion/correction results against the pinned cdict baseline. Load the converted dictionary with the project's pinned librime; conversion success alone does not establish working candidates or equivalent correction.

Acceptance:

- Record entry counts, duplicate rules, character/case preservation, weight mapping and unsupported metadata.
- Repeated conversion from the same inputs produces identical source/manifest bytes.
- Real engine checks cover exact lookup, prefix completion, representative spelling errors, empty/unknown input and candidate selection; report unsupported behavior explicitly.
- Record initialization, warm query and storage observations using comparable workloads. Do not infer device touch latency from host/emulator measurements.
- Decide whether to extract a reusable conversion/data project. If extracted, pin its releases in MIANCAK; do not couple it to Android views, gestures or user learning files.

Any temporary cdict compatibility build should consume the same maintained common-format data. Do not introduce independently edited cdict and Rime word lists.

## D3: multilingual data packages and optional suggestions

After the multilingual conversion pilot, reuse or extend #22's word-table deployment boundary for the added language data and explicit language/suggestion settings. This D3 gate does not postpone Chinese import/switching of existing tables. Validate language-package versions/hashes, supported data components, resource limits and update recovery. Keep stable user-learning data separate from replaceable shared dictionaries.

Retain direct English as a complete mode. Suggestions/composition for another language must be explicitly selected and must not silently add autocorrect, punctuation conversion or mandatory candidate confirmation to the direct path. Preserve exact literals, literal digits, one-finger Shift, explicit language switching, editor generations and sensitive/no-learning policy.

Acceptance: package/install/update/failure tests, preserved settings/user data, actual Android candidate/editor assertions and the relevant existing emulator gates. Import data/configuration only through the defined profile; this does not introduce a general Lua/native executable plugin loader.

## D4: language expansion and device validation

Add an accented Latin-script pilot, such as French or German, before claiming general multilingual support. Define how apostrophes, diacritics, case, word boundaries and the language's expected correction behavior interact with the common format and routing.

Expand coverage incrementally. Each added language records source/license/version, vocabulary and behavior limits, candidate/editor cases, data footprint and physical ARM-device observations. Non-Latin scripts require their own input/schema validation; adding a word list alone is insufficient.

Acceptance: offline use, exact punctuation/digits, editor/cursor changes, rotation/restart, update preservation and measured device behavior for the claimed language scope. Document backend-specific differences.

## Scheduling and tracking

Keep the three accepted workstreams separate: Chinese input correctness/quality (#20), existing Rime word-table import/addition/switching (initial #22 delivered in #30), and long-term common-source/cdict migration (#18). The delivered import profile has no complete external scheme, plugin/model, crawler or converter prerequisite. Formal Chinese word-package selection and quality improvements now follow the [repair plan](LAYOUT_REPAIR_PLAN.md).

Physical ARM/editor validation belongs to Chinese usability acceptance. Stable signing/versioned delivery follows usability acceptance. The cross-language converter or a separate data project must not delay use of existing compatible Rime word tables.

Use scoped Issues and short-lived implementation branches. #22 is closed after its delivered profile passed the actual APK gates; #18 and the remaining #20 usability/device work are not completed by this roadmap. Documentation does not deliver runtime changes or new acceptance gates. Follow [DEVELOPMENT_WORKFLOW.md](DEVELOPMENT_WORKFLOW.md); the [INTERACTION_CONTRACT.md](INTERACTION_CONTRACT.md) and its repair-plan amendment govern future work.
