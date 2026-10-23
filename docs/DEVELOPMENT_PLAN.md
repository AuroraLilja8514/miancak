# Development Plan

Reviewed on 2026-10-23 against the committed source, interaction contract, architecture and CI. This plan turns the architecture direction into small implementation gates; the interaction contract remains normative.

## Current state

- Project identity, interaction rules, architecture, Issue/PR workflow and baseline CI exist.
- The inherited frontend is synchronized through Unexpected Keyboard `c87d9b8fbd501197286eaafeae88278dfbdd56b7`.
- The semantic routing foundation is merged. Its executor and dedicated touch UI now connect it to the inherited IME frontend.
- M1–M4 are implemented: pinned Rime/JNI, offline pinyin data, serialized controller, frontend adapter, immutable snapshots and touch candidates. The complete input path passed API 23/API 35 emulator tests, process restart and debug/minified-release builds. Actual results and limits are recorded in [TESTING.md](TESTING.md).
- The current APK provides Chinese Rime composition and direct English input. Inherited language layouts remain available, but multilingual suggestions/correction and general dictionary import are not implemented in the new input path.
- A common Rime-compatible dictionary source format is accepted future work, tracked in [issue #18](https://github.com/AuroraLilja8514/miancak/issues/18) and [DICTIONARY_ROADMAP.md](DICTIONARY_ROADMAP.md). The possible cdict word-list conversion project is deferred.

The architecture direction is still appropriate: preserve the frontend, add a small semantic boundary and integrate librime directly. A gesture rewrite, Kotlin migration or Fcitx5 runtime would add work without resolving the current gaps.

## Changes to the development sequence

1. Establish executable semantic rules before coupling UI code to JNI. A fake engine makes ordering, exact literals and language routing testable without native build failures obscuring behavior.
2. Prove a pinned Android librime build and minimal offline data deployment before investing in the candidate UI. Native dependencies, ABI support, data size and startup behavior are feasibility gates.
3. Implement serialized execution and editor lifetime handling before wiring keys into a real engine. A pure routing plan is insufficient to prevent asynchronous reordering or commits to the wrong editor.
4. Add candidate UI only after immutable, versioned engine results exist. Candidate taps must refer to the exact snapshot displayed.
5. Keep each stage in a narrow PR with its own acceptance criteria. Do not add empty CI checks or broaden a sync PR into feature work.

## M1: semantic routing foundation

Implementation: `srcs/juloo.keyboard2/input/SemanticAction.java` and `InputRouter.java`.

The planner accepts a language mode, authoritative composition state and an explicit semantic action. It returns immutable ordered operations and a resulting language mode; it performs no I/O or engine/editor calls.

| Action | No active composition | Active composition |
| --- | --- | --- |
| Engine text, Chinese | Send to engine | Send to engine |
| Engine text, English | Commit exact text | Confirm, then commit exact text |
| Literal text, either mode | Commit exact text | Confirm, then commit exact text |
| Backspace | Editor backspace | Engine backspace |
| Space | Commit ordinary space | Engine space/confirmation |
| Language toggle | Change mode | Confirm, then change mode |

Empty text is a no-op. Digits/punctuation are literal by default at the future frontend boundary; an explicitly declared engine punctuation/digit action stays an engine action. Shift latch/lock stays in the frontend. Explicit shifted ASCII can be classified as Literal without changing language mode.

Acceptance gate: existing `unit` CI executes behavioral tests for exact ASCII/full-width/Unicode literals, Chinese/English routing, selected-candidate-before-literal ordering, composition-aware space/backspace, language switching and immutable plans. No runtime wiring is claimed at this stage.

## M2: Android Rime feasibility and application identity

Deliver a small native build/session spike before committing to a large UI integration:

- Pin librime and transitive source versions, NDK/CMake/toolchain versions and the initial supported Android ABI set.
- Build/load the bridge and initialize one persistent session; exercise engine input, preedit, candidates, selection, confirmation and clear operations.
- Package one minimal, licensed Chinese schema/data set and deploy it using a version/hash manifest outside ordinary key handling.
- Keep shared/user data separate, preserve learning data across upgrades and keep user data in credential-protected storage.
- Add accurate source, version, license and build/data provenance when components actually enter the repository or build.
- Give MIANCAK a distinct application ID and visible app identity before device integration so it can coexist with Unexpected Keyboard. Avoid a broad inherited Java package rename.

Acceptance gate: reproducible native debug APK for the declared ABIs, an offline Chinese composition smoke test, repeat-start/deployment checks, documented startup/data footprint and real native CI coverage. Missing artifacts or an unsupported ABI must fail visibly rather than silently disabling Chinese input.

## M3: serialized controller and editor transactions

Implement the executor around the planner and the tested composition-engine boundary:

- Route only after preceding queued engine actions finish, using engine truth rather than a possibly stale UI preedit snapshot. Never calculate an entire queue of plans from one old composition flag.
- Put literal commits and language switches behind pending engine work when ordering requires it. Keep an idle English/literal fast path when there is no composition or queued ordering dependency; do not block the Android UI thread waiting for Rime.
- Resolve engine confirmation first, then apply the resulting editor operations in order on the editor thread, using a bounded batch edit. Do not hold an Android batch edit open across asynchronous engine work.
- Apply a plan's resulting mode only after its operations succeed. Failed confirmation must not append the literal or prematurely switch languages. Specify recoverable behavior for unconsumed engine keys and engine initialization failures.
- Associate every queued operation/result with an editor generation. A new field, finish-input, service shutdown or invalidated connection must invalidate stale results and candidate taps.
- Keep view recreation separate from editor/session lifetime. A persistent native runtime does not permit composition from one editor to leak into another.
- Define cursor/selection-change behavior, Enter/editor actions, shortcuts and sensitive-field/no-learning behavior before routing those events through the engine.

Acceptance gate: deterministic fake-worker/editor tests for rapid `Engine("nihao") -> Literal("。") -> English letter`, switching fields with pending work, stale results, failure during confirmation, selection changes, reconnects and shutdown. Tests must establish editor output and ordering, not merely JNI call success. Add `interaction-tests` when this executable controller exists.

## M4: frontend adapter and touch candidates

Map existing post-modifier key values into semantic actions through a small adapter. Keep geometry, gestures and one-finger Shift latch/lock ownership in the existing frontend; keep English direct output independent of inherited suggestions/autocorrect.

Introduce immutable preedit/candidate snapshots containing editor generation and snapshot revision. Select candidates by a tap bound to that revision; reject stale taps instead of applying an old index to a new list. Keep the Chinese strip separate from English suggestion behavior, with stable height and horizontal access to more candidates. Literal digits remain digits.

Acceptance gate: fake-engine integration plus device checks for identical Chinese/English geometry, one-finger switching, Shift bypass, literal punctuation/digits, candidate paging/selection and stable keyboard height. Native code must not be called directly from views.

## M5: device validation and usable offline MVP

Validate mixed Chinese/English input, exact punctuation, deletion, confirmation, language switching, editor changes and keyboard recreation on representative standard and unusual editors. Include password/numeric/no-suggestion fields, multiline/editor-action behavior, rotation and process restart; document supported behavior and limitations.

Measure touch-to-routing, engine, candidate and editor latency on a real device; record cold/warm startup and memory/data footprint. Verify the input path works offline and deployment/session creation does not run per key. Keep release signing/store automation deferred until the IME is usable.

Acceptance gate: documented device results, passing generated/unit/debug/native/interaction checks applicable to the implemented code, no known ordering or cross-editor data leak, and a reproducible debug APK demonstrating the complete Chinese-input path.

## Implementation status and next development

M1–M4 have implementations and the existing automated scenarios pass. This establishes those test cases, not acceptance of everyday Chinese input. The maintainer reports that Chinese input is not yet done; the failing sequence and root cause still require reproduction. Chinese usability acceptance remains open in [issue #20](https://github.com/AuroraLilja8514/miancak/issues/20). The tests previously found and fixed preference migration ordering, partial native commits and unsigned release configuration; those fixes do not close the new report.

The plan should keep implementation completion separate from validation scope. API 23/API 35 x86_64 interaction coverage and three-ABI builds are established; physical ARM-device touch latency, cold/warm startup, memory and unusual vendor editors still require device evidence. Release signing/store publication remain outside the MVP.

Continue with Chinese usability as the first application priority:

| Stage | Next work | Completion evidence |
| --- | --- | --- |
| C0: reproduce and fix Chinese input | Obtain the actual input -> observed result -> expected result, device/editor context; fix initialization, preedit, candidates, confirmation or editing defects identified by that reproduction | Reproducible report, a meaningful regression and a repeat of the affected app/device scenario |
| C1: Chinese quality and vocabulary control | Evaluate common phrases/names/sentences; add manual phrase editing and persistent external Rime dictionary import/redeployment | A documented Chinese corpus, visible expected candidates, add/edit/delete/restart/upgrade cases, preserved learning and failed-import recovery |
| C2: external Chinese data compatibility | Prepare a traceable MediaWiki-derived sample; assess Wanxiang data-only reuse separately from full scheme/model/Lua integration | Reviewed readings/weights, source/license metadata, actual engine/editor results and a pinned compatibility report stating unsupported features |
| M5 acceptance | Validate the corrected Chinese path on physical ARM devices and representative editors; measure startup, latency, memory and lifecycle behavior | Device/Android/app versions and measured results; distinguish real device execution from ABI build inspection |
| M6: test/release delivery | After Chinese usability acceptance, define stable signing/version/upgrade policy and durable APK delivery | Install/upgrade evidence, preserved settings/user data and signing isolated from PR code |
| D1–D4: future multilingual dictionaries | Continue the common-source-format/cdict converter workstream and later multilingual integration | The format/conversion/runtime/package/language gates in [DICTIONARY_ROADMAP.md](DICTIONARY_ROADMAP.md) |

Track C0–C2 in [issue #20](https://github.com/AuroraLilja8514/miancak/issues/20). The current APK enables Rime user learning in normal fields, but has no manual phrase manager, external dictionary import/redeploy UI or general scheme selector. Its asset loader accepts packaged flat files, the JNI selects two fixed schemas, and the native build does not include librime-lua. Engine capabilities must not be described as shipped app features.

Chinese dictionary extension can use existing Rime formats and must not wait for the English/cdict conversion pilot or a separate data project. Wanxiang vocabulary reuse and complete Wanxiang scheme support are different deliverables; evaluate the selected version's codes, dependency tables, components and models before implementation. No arbitrary executable plugin loader is implied.

Keep existing semantic/native/UI boundaries as a baseline while fixing reported behavior. Necessary interaction/dependency changes require explicit scope and tests; release infrastructure and a framework rewrite do not resolve an unverified Chinese input failure.

## Future dictionary workstream

The accepted long-term target is a common Rime-compatible `.dict.yaml` source format across maintained language dictionaries. Language schemas and provenance/package manifests remain separate data. Versioned runtime artifacts may differ by backend; if cdict remains during transition, generate its compatibility artifacts from the same maintained source rather than editing two independent word lists.

[DICTIONARY_ROADMAP.md](DICTIONARY_ROADMAP.md) defines D1–D4, acceptance criteria and possible extraction of the conversion/data tooling into an independent project. This is a data-pipeline investigation, not a promise that Rime automatically reproduces cdict correction or that existing binary `.dict` files can be renamed/imported. Preserve original source inputs and document metadata that conversion cannot represent.

Tracking: [issue #18](https://github.com/AuroraLilja8514/miancak/issues/18) remains open for implementation. When a phase starts, create a scoped child Issue and a short-lived branch such as `feat/dictionary-source-contract`, `feat/dictionary-converter-pilot` or `feat/dictionary-packages`; link it back to the tracking Issue. A planning branch does not establish a long-lived integration branch.

Do not reopen the completed semantic/native/UI architecture stages or introduce a gesture/framework rewrite without a demonstrated requirement.

Chat-only requirements that are not present in the committed specification must be reconciled explicitly before changing the interaction contract. Do not infer that this plan or a passing planner test constitutes a working Chinese keyboard.
