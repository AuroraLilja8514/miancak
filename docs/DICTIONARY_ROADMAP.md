# Shared Dictionary Roadmap

Accepted on 2026-10-23. Status: **future work; implementation deferred**. Tracking: [issue #18](https://github.com/AuroraLilja8514/miancak/issues/18). This document records the maintainer's requested direction without changing the current Chinese prototype.

## Accepted direction

Maintain language dictionaries in one common Rime-compatible `.dict.yaml` source format. Keep language/schema configuration, package metadata and provenance separate. Rime's compiled artifacts and any transitional cdict binary artifacts are generated outputs; sharing source data does not require every language to use the same runtime backend.

Investigate converting the source word lists used by cdict. The converter/data pipeline may later become an independent project with a small, versioned interface consumed by MIANCAK. Decide its repository boundary after a useful pilot exists. No separate repository or runtime migration is created by this plan.

## Current baseline and source boundaries

- The MVP packages the unmodified `rime/rime-pinyin-simp` dictionary at the revision in `native/sources.lock.json`. Chinese composition uses librime; English uses the direct path.
- Unexpected Keyboard's inherited dictionary pipeline is [Helium314/aosp-dictionaries](https://codeberg.org/Helium314/aosp-dictionaries) -> [Julow/Unexpected-Keyboard-dictionaries](https://github.com/Julow/Unexpected-Keyboard-dictionaries) -> [cdict](https://github.com/Julow/cdict) binary dictionaries. Its catalog/downloader is distinct from keyboard layouts.
- The inherited catalog is not evidence that multilingual completion/correction is implemented in MIANCAK's new input path. A layout, a word list and an input algorithm are separate deliverables.
- A compiled cdict `.dict` file is not a Rime dictionary source. Start conversion from traceable source word lists, not a renamed binary.
- Preserve original upstream inputs and notices. The common-format requirement applies to maintained dictionary data; upstream originals remain archived or reproducibly fetchable as provenance inputs.

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

## D3: application data packages and optional suggestions

After the pilot, specify the app's data-package boundary, deployment lifecycle and explicit language/suggestion settings. Package validation must cover declared versions/hashes, supported data components, resource limits and invalid/interrupted update recovery. Keep stable user-learning data separate from replaceable shared dictionaries.

Retain direct English as a complete mode. Suggestions/composition for another language must be explicitly selected and must not silently add autocorrect, punctuation conversion or mandatory candidate confirmation to the direct path. Preserve exact literals, literal digits, one-finger Shift, explicit language switching, editor generations and sensitive/no-learning policy.

Acceptance: package/install/update/failure tests, preserved settings/user data, actual Android candidate/editor assertions and the relevant existing emulator gates. Import data/configuration only through the defined profile; this does not introduce a general Lua/native executable plugin loader.

## D4: language expansion and device validation

Add an accented Latin-script pilot, such as French or German, before claiming general multilingual support. Define how apostrophes, diacritics, case, word boundaries and the language's expected correction behavior interact with the common format and routing.

Expand coverage incrementally. Each added language records source/license/version, vocabulary and behavior limits, candidate/editor cases, data footprint and physical ARM-device observations. Non-Latin scripts require their own input/schema validation; adding a word list alone is insufficient.

Acceptance: offline use, exact punctuation/digits, editor/cursor changes, rotation/restart, update preservation and measured device behavior for the claimed language scope. Document backend-specific differences.

## Scheduling and tracking

Chinese input correctness, usable candidates/sentences and Chinese vocabulary extension are the current application priority, tracked in [issue #20](https://github.com/AuroraLilja8514/miancak/issues/20). The maintainer's Chinese usability report is unresolved; existing emulator scenarios do not establish product acceptance. Manual phrases and Rime dictionary imports must not wait for an English/cdict converter or a separate project.

Physical ARM/editor validation belongs to that Chinese acceptance work. Stable signing/versioned delivery follows usability acceptance. D1 research may inform Chinese data handling, but multilingual conversion/integration remains deferred; D3 still depends on the format, pilot and explicit interaction design. Evaluate MediaWiki-derived data and Wanxiang vocabulary/full-scheme compatibility within the Chinese workstream before making support claims.

Use scoped Issues and short-lived branches for D1–D4. Suggested branch names are `feat/dictionary-source-contract`, `feat/dictionary-converter-pilot`, `feat/dictionary-packages` and `feat/dictionary-language-pilot`. These are future branch names, not pre-created long-lived integration branches.

Keep issue #18 open when this roadmap is merged. Close it only when the implementation gates are delivered or its scope is explicitly revised. Follow [DEVELOPMENT_WORKFLOW.md](DEVELOPMENT_WORKFLOW.md); the [INTERACTION_CONTRACT.md](INTERACTION_CONTRACT.md) remains normative.
