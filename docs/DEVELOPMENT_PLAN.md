# Development Plan

Reviewed on 2026-10-24 against the committed source, interaction contract, architecture, CI and maintainer feedback. This plan turns the architecture direction into small implementation gates; the interaction contract remains normative. The U0–U5 work below is planning, not a claim that the current APK implements it.

## Current state

- Project identity, interaction rules, architecture, Issue/PR workflow and baseline CI exist.
- The inherited frontend is synchronized through Unexpected Keyboard `c87d9b8fbd501197286eaafeae88278dfbdd56b7`.
- The semantic routing foundation is merged. Its executor and dedicated touch UI now connect it to the inherited IME frontend.
- M1–M4 are implemented: pinned Rime/JNI, offline pinyin data, serialized controller, frontend adapter, immutable snapshots and touch candidates. The complete input path passed API 23/API 35 emulator tests, process restart and debug/minified-release builds. Actual results and limits are recorded in [TESTING.md](TESTING.md).
- The current APK provides Chinese Rime composition and direct English input. Inherited language layouts remain available, but multilingual suggestions/correction and general dictionary import are not implemented in the new input path.
- The maintainer observed that Chinese punctuation became accessible after changing the layout: a normal first-install path can select the inherited system layout rather than MIANCAK QWERTY. Source review identifies an initialization-order path consistent with this report; a clean-device UI reproduction and fix remain outstanding. Existing touch tests explicitly select MIANCAK QWERTY and do not establish correct first-run defaults.
- The current candidate view exposes the engine page through a horizontal scroller; it does not measure a compact row to show only whole candidates that fit, or provide an expanded candidate panel.
- Shift double-tap locking already exists but defaults to off. The default MIANCAK layout reserves Shift northeast for optional Caps Lock. Custom layouts are edited as XML; the extra-key settings are not a visual editor for individual key positions.
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

M1–M4 have implementations and the existing automated scenarios pass. Everyday Chinese usability remains open in [issue #20](https://github.com/AuroraLilja8514/miancak/issues/20). The conversation also establishes a missing feature: importing, adding and switching existing Rime word tables. That future feature has its own scope in [issue #22](https://github.com/AuroraLilja8514/miancak/issues/22); it is separate from correctness/quality defects and from installing third-party input schemes.

The plan should keep implementation completion separate from validation scope. API 23/API 35 x86_64 interaction coverage and three-ABI builds are established; physical ARM-device behavior/performance still needs device evidence. Record a concrete reproduction for each correctness defect being fixed without making unrelated feature planning depend on a new defect report.

Keep these workstreams distinct:

| Workstream | Work and acceptance | Scope boundary |
| --- | --- | --- |
| Chinese usability: #20 | Exercise representative phrases/names/sentences and editor lifecycles; reproduce/fix verified defects with regressions; collect physical ARM/editor measurements | No dictionary-management or third-party scheme feature is implied |
| Chinese-focused frontend: U0–U5 below | Fix first-run defaults; review punctuation/Shift/page mappings; align settings and help; scope a future visual key editor | Default-layout simplification does not imply removal of symbol pages, all upstream resources or existing custom layouts |
| Existing Rime word tables: #22, future feature | Import compatible existing tables; replace/select a base vocabulary; add/enable/disable supplementary tables; redeploy offline; preserve learning and recover from failure; prove actual candidate/editor changes | MIANCAK retains its own input logic/schema. No complete third-party scheme, Lua, model, crawler or cdict-converter dependency |
| Common source format/cdict: #18, future work | Define the cross-language maintained source profile, prototype conversion and expand language coverage using D1–D4 | Does not gate use of existing Rime tables in #22; possible separate tooling project remains deferred |
| Test/release delivery: M6 | After Chinese usability acceptance, define stable signing/version/upgrade policy and durable APK delivery | Release infrastructure is separate from both input correctness and word-table support |

The current APK enables Rime user learning in normal fields and packages one fixed vocabulary. It has no word-table import/add/switch UI. #22 should first state the compatible data profile and dependency handling, then add base/add-on selection and deployment, and verify real candidate differences, restart/preservation and failure recovery. A prepared MediaWiki-derived table or vocabulary from any compatible Rime project is a possible input; neither Wanxiang nor a complete external scheme is required.

Manual phrase editing, crawling/preparing MediaWiki data and complete third-party scheme support are separate potential tasks. Do not bundle them into #22 or use their missing components to block vocabulary-data support. #22 can use existing Rime formats before the long-term common-format/cdict migration.

Keep existing semantic/native/UI boundaries while fixing verified behavior. Any vocabulary switch must deliberately handle pending composition/results and preserve the interaction contract.

## Chinese input and frontend follow-up: U0–U5

This section records the current design discussion. Continue reviewing the complete key map and input semantics before implementing runtime changes. Keep the established engine/controller boundaries; refer to Rime, Fcitx5 Android and Trime for interaction behavior where useful, without making a new runtime framework a prerequisite.

| Order | Deliverable | Acceptance boundary |
| --- | --- | --- |
| U0, first | First-install defaults and configuration synchronization | The delivered APK works through both normal enablement orders on a previously unused device image; existing explicit layout choices survive upgrades |
| U1 | Chinese punctuation, language-switch position and Shift behavior | A reviewed normal/Shift key map; exact editor output; usable sequential one-finger actions; no occupied slot silently overwritten |
| U2, same initial usability batch | Compact candidates and expansion | Whole candidates fit the collapsed row; every remaining candidate stays reachable and selects the correct engine item |
| U3 | Chinese-focused layout/page/settings organization | One clear default for the current pinyin/English target; explicit page access; migration-safe reduction of unused choices |
| U4, with each affected feature | Launcher, settings, introduction and documentation | The shipped instructions and screenshots describe that version's actual defaults, gestures, pages and supported data management |
| U5, future feature | Visual key mapping | Edit a key/slot/action with preview, validation, persistence, reset and import/export; no gesture or geometry rewrite is required |

### U0: first-install defaults are a product path

`SettingsActivity.onCreate()` currently calls `Config.migrate()` directly. On empty preferences, that migration writes a version and the inherited system layout. `MiancakPreferences.initialize()` then sees those keys and skips the MIANCAK first-install default. Settings and IME also use preferences that are copied between credential/device-protected storage on newer Android versions. Verify both settings-first and IME-first entry orders; a later settings visit must not overwrite IME initialization.

Use one coherent initialization policy for all entry points and make preference ownership/synchronization explicit. Do not treat the migration version alone as evidence that a user selected a layout. Do not fix this by forcing MIANCAK QWERTY on every start or by deleting the system-layout resource. Preserve deliberate existing layout choices and custom XML. Any repair for previously affected installations must distinguish what can be inferred from what requires a user choice.

The clean-install acceptance suite must use a new device image without restored app data and the actual delivered APK. It must drive enablement/selection through Android UI, not pre-seed preferences or use `ime set` to bypass onboarding. Retain the prepared-layout tests as component/integration coverage. The full route and matrix are specified in [TESTING.md](TESTING.md).

### U1: punctuation, Shift and language switching

The working interpretation of "bottom row" is the lowest letter row, `Shift Z X C V B N M Backspace`, rather than the separate Ctrl/Fn/Space control row. Review a complete table of center/corner assignments, ordinary/Shift output and semantic actions before changing XML or modifier behavior.

- Put Chinese punctuation in northwest positions on that letter row and keep related ASCII symbols on the same key. The existing `c` northwest `。` / southwest `.` and `v` northwest `，` / southwest `,` illustrate the high-co-occurrence pairs: each keeps its own explicit action.
- For lower-co-occurrence Chinese/ASCII pairs such as parentheses and quotation marks, use the same position with sequential Shift switching. Display the resulting symbol accurately. Specify opening/closing forms separately; do not introduce contextual punctuation guessing. Some related ASCII symbols currently live on other rows, so review their relocation rather than merely adding disconnected Chinese labels.
- Preferred draft: remove the dedicated Caps Lock corner from the default main layout, put `language_switch` at Shift northeast and enable the existing double-tap Shift lock by default for new installations. Keep tap-to-latch and long-press locking. Tapping locked Shift must unlock it, with a visible lock state. Moving optional Caps Lock to Shift southeast remains an advanced/custom-layout alternative, not another mandatory default key. Preserve an existing user's explicit lock preference and custom slot assignment.
- The northeast swipe must execute the language action without accidentally latching/locking Shift; double-tap/long-press Shift must not switch languages. Preserve the contract's composition-confirmation ordering when changing modes. Shifting symbols and producing uppercase ASCII must not change the language mode.
- Use a Chinese single-quote label, proposed `’` (U+2019), for the pinyin syllable-separator action. Map that semantic action to the delimiter required by the current Rime schema (currently ASCII apostrophe); do not substitute curly quotes throughout dictionary codes or commit a quotation mark when requesting separation. Specify idle and English-mode behavior in the key map. Keep literal `‘`, `’` and ASCII apostrophe actions distinguishable from the engine action.

Acceptance includes first-visible key labels, all approved punctuation pairs, composing versus idle input, mixed Chinese/ASCII punctuation, separator/disambiguation examples, latch/lock/unlock and northeast swipes in both language modes. These are proposed defaults until implemented and verified, not current APK instructions.

### U2: compact candidates and expansion

Measure the available width after reserving controls/padding, using the actual font size and touch target dimensions. Show only complete candidate items that fit the collapsed row; define a bounded display for a single overlong item and expose its complete text in expansion. Provide explicit expand/collapse access and keep the IME's allocated height stable, for example by using the letter-key area for the expanded view.

An engine page is not a screen row. Expansion must retain candidates omitted from the compact row as well as access to subsequent engine pages. Keep candidate identity, page-local index, editor generation and snapshot revision associated with each selection; resizing or expansion must not create missing/duplicate entries or turn an old tap into selection of a different candidate.

Acceptance covers narrow/wide screens, portrait/landscape, larger fonts, long phrases, engine paging, expansion/collapse and exact editor commits. Update the candidate interaction contract and instructions in the implementation PR to describe the approved navigation.

### U3: distinguish main layouts, functional pages and language mode

The current implementation has separate mechanisms:

| Mechanism | Current implementation | Product implication |
| --- | --- | --- |
| Main text layouts | Generated metadata for `srcs/layouts/*.xml`, selected through `LayoutsPreference`; `switch_forward`/`switch_backward` cycle configured layouts | MIANCAK QWERTY can be the sole normal default without exposing the entire inherited catalog |
| Greek/math and numeric pages | `switch_greekmath` / `switch_numeric` load dedicated XML through `Keyboard2.setSpecialLayout()`; `switch_text` returns to text | These useful symbol pages are not foreign-language main layouts and need their own mapping/access review |
| Emoji and clipboard | Separate panes replace the input view and return to the keyboard container | Include these in page navigation and help, rather than assuming every page is a layout XML |
| Chinese/English | The semantic `language_switch` action changes routing mode | Switching modes must keep the same ordinary key geometry and must not cycle main layouts |

The current target is Chinese pinyin plus direct English using a shared QWERTY geometry. That makes one default appropriate; it does not establish that every Chinese user or a future double-pinyin/nine-key feature uses an identical layout.

First simplify the normal settings path: expose the MIANCAK default, the reviewed punctuation/symbol/numeric/emoji pages and clear gesture controls. Move inherited main-layout selection and raw XML editing into an advanced area while preserving explicit selections. Hiding choices is a usability change, not an APK-size reduction.

Physical removal of unused layout resources can follow a measured inventory and a scoped cleanup. Check IME subtype/default-layout references, generated metadata/generators, hardcoded fallbacks, tests and saved layout IDs. Define an explicit migration/fallback before removing a referenced resource; retain upstream history/licenses and keep future synchronization reviewable. Do not delete Greek/math pages merely because the product targets Chinese users, and do not conflate layout cleanup with dictionary-source migration.

### U4: settings, launcher and documentation are part of delivery

Every affected runtime PR must update its associated user-facing surfaces, not leave that work for an unspecified later documentation pass:

- Launcher/first-run guidance: enabling/selecting MIANCAK, verifying the default layout, trying Chinese input, finding help and recovering the default.
- Typing settings: the actual Shift latch/double-tap/long-press behavior, lock feedback and language-switch gesture; preserve explicit existing preferences when choosing new defaults.
- Layout/page settings: normal versus advanced choices, numeric/symbol/Greek/math/emoji/clipboard entry and return, and any required migration notices.
- In-app introduction, gesture examples/animations, accessibility descriptions and relevant Chinese/localized strings: use the shipped labels/positions and show the main Chinese operations.
- Dictionary wording: the inherited launcher currently opens `DictionariesActivity`; do not present cdict management as Rime word-table import. Clearly label its effective scope or remove the misleading normal-flow entry. The actual Rime import/add/switch feature remains #22.
- README, interaction contract, key-value/custom-layout guidance and testing documentation: record current behavior, compatibility and actual evidence. Planned behavior must remain labeled as planned until shipped.

Acceptance walks through installation, first input, settings/help and return to input using only the shipped UI. Include correct simplified-Chinese text and a fallback-language check, consistent key labels and accessibility descriptions, and screenshots that match that version. U4 ships with U0–U3 where applicable rather than blocking all documentation until every feature is finished.

### U5: future visual key mapper

The inherited XML dialog and extra-key list allow customization but do not offer an approachable visual mapping workflow. Plan a dedicated follow-up issue/short-lived branch after the basic Chinese flow is usable; this editor is not a prerequisite for fixing default punctuation, and it is independent of the word-table workstream.

Start with a preview of the selected main layout or supported functional page. Tap a key and select center/corner, then choose an exact literal or a named semantic action (language switch, separator, page navigation, editor action). Preview ordinary/Shift output and resolve conflicts explicitly. Preserve a reachable settings/reset/page-return path.

Reuse the existing key representation/parser and semantic boundary. Store versioned per-layout/page mappings or overrides so an application update does not silently erase customizations. Include save/cancel, per-key and whole-layout reset, import/export, validation errors and an advanced XML view. State which panes are supported; emoji/clipboard views do not automatically become editable key grids. The first version need not support freeform geometry dragging or a gesture-engine rewrite.

Acceptance must prove that saved mappings drive actual key actions and survive restart/upgrade, literals remain exact, separator/language actions keep their semantics, malformed imports do not replace a valid configuration, and reset recovers an operable keyboard.

## Future dictionary workstream

The accepted long-term target is a common Rime-compatible `.dict.yaml` source format across maintained language dictionaries. Language schemas and provenance/package manifests remain separate data. Versioned runtime artifacts may differ by backend; if cdict remains during transition, generate its compatibility artifacts from the same maintained source rather than editing two independent word lists.

[DICTIONARY_ROADMAP.md](DICTIONARY_ROADMAP.md) defines D1–D4, acceptance criteria and possible extraction of the conversion/data tooling into an independent project. This is a data-pipeline investigation, not a promise that Rime automatically reproduces cdict correction or that existing binary `.dict` files can be renamed/imported. Preserve original source inputs and document metadata that conversion cannot represent.

Tracking: [issue #18](https://github.com/AuroraLilja8514/miancak/issues/18) remains open for implementation. When a phase starts, create a scoped child Issue and a short-lived branch such as `feat/dictionary-source-contract`, `feat/dictionary-converter-pilot` or `feat/dictionary-packages`; link it back to the tracking Issue. A planning branch does not establish a long-lived integration branch.

Do not reopen the completed semantic/native/UI architecture stages or introduce a gesture/framework rewrite without a demonstrated requirement.

Chat-only requirements that are not present in the committed specification must be reconciled explicitly before changing the interaction contract. Do not infer that this plan or a passing planner test constitutes a working Chinese keyboard.
