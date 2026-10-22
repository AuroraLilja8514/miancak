# Development Plan

Reviewed on 2026-10-22 against the committed source, interaction contract, architecture and CI. This plan turns the architecture direction into small implementation gates; the interaction contract remains normative.

## Current state

- Project identity, interaction rules, architecture, Issue/PR workflow and baseline CI exist.
- The inherited frontend is synchronized through Unexpected Keyboard `c87d9b8fbd501197286eaafeae88278dfbdd56b7`.
- The semantic routing foundation is merged. Its executor and dedicated touch UI now connect it to the inherited IME frontend.
- Rime/JNI and packaged pinyin data are implemented and passed native emulator tests. The serialized controller, frontend adapter and touch candidates are implemented on the integration branch; full Android interaction verification is tracked in TESTING.md.

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

## Immediate next work

M1 and M2 are merged with passing CI. Complete the integrated M3/M4 emulator matrix and process-restart checks, fix any failures, and record actual M5 validation/measurement scope before merging the MVP. Physical-device performance remains a separately identified follow-up because this execution environment supplies emulators.

Chat-only requirements that are not present in the committed specification must be reconciled explicitly before changing the interaction contract. Do not infer that this plan or a passing planner test constitutes a working Chinese keyboard.
