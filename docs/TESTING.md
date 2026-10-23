# MVP Testing

The initial offline Chinese-input implementation follows `INTERACTION_CONTRACT.md`. CI artifacts contain unit XML reports, emulator logs/screenshots and scoped native latency measurements. Device images run on independent GitHub-hosted runners; no user environment is required.

## Verification layers

| Layer | Coverage | Execution |
| --- | --- | --- |
| Generated data | Layout/Compose/method outputs stay current | `generated` |
| Pure semantic rules | Chinese/English routing, literals, queued ordering, editor generations, stale candidates, failures, cursor acknowledgements and shutdown | Local JDK 17 + `unit` |
| Layout declarations | Explicit Engine/Literal identity, custom symbols and modifier behavior | `unit` |
| Native package | Debug/minified release, declared arm64-v8a/armeabi-v7a/x86_64 libraries, 16 KiB ELF alignment, hashed dictionary/provenance, release JNI names and fixture exclusion | `build-debug` + `check_native_apk.py` |
| Real engine and Android policy | Chinese candidates/selection, confirmation, partial commits, empty input, backspace, clear, reinitialization, private schema, data/user preservation, preference migration and protected-field policy | Emulator instrumentation |
| Actual IME | Physical taps/swipes, exact punctuation/digits, Shift latch/lock, direct English, candidate touch/paging, composing Space/Backspace, multiline Enter, field/cursor changes, theme/view recreation, rotation, protected fields and WebView | Emulator instrumentation |
| Process restart | Force-stop then reopen the IME and compose again without reinstalling | Emulator instrumentation |

The instrumented matrix targets API 23 and API 35, x86_64. Each run disables Android guest Wi-Fi/mobile data before testing. Native tests run before UI tests so their isolated initialization/error/learning results do not share a live IME session.

## First-install implementation and acceptance

The existing `ImeInteractionTest` initializes preferences, explicitly selects MIANCAK QWERTY and enables/selects the IME using shell commands. It validates actions from that prepared state. It does not establish that installation, launcher/settings visits and Android enablement produce the correct first keyboard for an ordinary user.

The maintainer reported that Chinese punctuation became accessible after changing the layout. The matching settings-first path has been corrected: SettingsActivity uses the IME's preference store and MIANCAK initializer before inflating preferences. API 24+ settings use device-protected storage directly, so a stale credential-protected settings copy no longer overwrites an IME-first default. Earlier prepared-state migration coverage did not establish this behavior.

`FirstInstallTest` now drives the shipped launcher/settings, Android's enable toggle and input-method picker, and the shipped try-here editor. It checks default-layout UI, actual Chinese/ASCII text, punctuation, default double-tap lock and a later settings visit, then repeats after force-stop/reopen. It never writes app preferences or uses shell IME selection; it only reads rendered geometry after the keyboard appears. `first_install_tests.sh` refuses a guest that already contains the app and records the delivered APK hash. Four independent fresh-image jobs cover both entry orders on API 23/API 35. These checks have been added; this source update does not yet claim a passing run. Chinese-locale, reboot and version-upgrade expansion remain C6 acceptance work.

C1 enables double-tap Shift for new installations and adds the language-switch corner. The APK interaction scenario checks punctuation/Shift pairs, quotation marks, `xi` + separator + `an` yielding “西安” without an early commit, and Shift-northeast language switching. C2 adds measured compact candidates, same-height expansion, expanded engine paging/selection and Back collapse. `CandidatePresentationTest` measures real Android TextViews at narrow/wide widths and 1.8 font scale, including overlong text and fixed-height expansion. Passing results for these additions must be recorded separately from the earlier MVP run.

The following remains the complete acceptance target; implementation/results must distinguish each covered phase from outstanding work:

| Phase | UI path and assertions |
| --- | --- |
| Previously unused image | Create a fresh emulator guest with no earlier app installation, snapshot/app-data restore or backup contamination. Record Android/API/ABI/locale and the delivered APK version/hash. Installing over a used guest is a separate upgrade case. |
| Application-first route | Install that APK, open its launcher/settings, then enable and select MIANCAK through Android UI. Do not write preferences, call an initialization helper or use `ime set` to bypass this path. |
| System-settings-first route | On another clean guest, enable/select from Android settings, use the IME first, then open the app's settings and return to typing. Defaults and later edits must not be overwritten. |
| First visible keyboard | Open a normal editor outside the debug fixture. Without manually choosing a layout, verify the intended MIANCAK layout, visible punctuation and language status; enter Chinese and compare the actual editor text. |
| Complete input journey | Exercise the approved punctuation/Shift pairs, separator, language switch and lock/unlock; verify ordered commits with active composition. Check compact candidate bounds and expanded access, including correct selection of candidates omitted from the compact row. |
| Persistence | Revisit settings, force-stop/reopen, restart the device and rotate. Verify layout/preferences and correct input remain; cover both languages and page entry/return. |
| Upgrade, separately | For U0, use a signing-compatible upgrade to preserve then-supported system/main/custom choices, lock settings and user data. For U3's explicit retirement, verify old built-in/system IDs migrate to MIANCAK QWERTY with a notice and retained selection record, valid independent custom configurations remain usable, and learning data is preserved. |
| Help and accessibility | Follow shipped enablement, gesture/page and reset instructions; verify Chinese text, fallback language, displayed key labels and accessibility descriptions agree with actual actions. |

Cover both entry orders on API 23 and API 35 with Chinese/English system locales; these versions exercise the inherited single-store and protected-storage paths. Add narrow/wide, portrait/landscape and larger-font cases for candidate fitting. Keep prepared-layout native/interaction tests as complementary coverage, not a substitute. Use an independent UI driver where needed so this suite does not initialize or rewrite the application's internal state.

Collect screenshots at enablement, first keyboard, actual committed text, candidate expansion and post-restart, together with step assertions and logs. Record environmental automation failures separately from product failures. ARM APK inspection and physical-device runtime acceptance remain different evidence; no new fresh-install or physical-device result is claimed here.

## Planned unified-page and phonetic acceptance

These future cases follow [LAYOUT_DESIGN.md](LAYOUT_DESIGN.md); none are reported as executed by this documentation update.

| Case | Required result |
| --- | --- |
| Retired main layouts | No old built-in catalog/source/resource references remain in the shipped layout system; generated metadata, subtype defaults and fallback paths resolve. Test migration separately from U0 first-run defaults. |
| Selector gesture | Enter southeast opens the page selector without a newline/editor action; cancel restores the original page and preedit. All enabled/available pages are listed, and dynamic panes expose the same selector. |
| Equivalent navigation | Reach each target by its shortcut and by the selector; compare page ID, effective language, return target, Shift/legacy-Fn state, indicators and actual output. Selecting the configured Fn target lights Fn regardless of route. |
| Composition and return | Page-only switching preserves composition; an explicit literal confirms before appending; Chinese/English profile changes honor confirmation ordering. Repeated Fn/Greek/numeric switches, selector cancel, rotation and view recreation must not strand a page or retain an unintended modifier. |
| Configuration/editor policy | Disable/reorder/edit pages and change shortcut targets; validate unavailable targets and recover defaults. Respect password/numeric/no-learning policies. Persist valid edits across restart/upgrade and reject malformed imports without replacing the prior configuration. |
| Future phonetic pages | Select the Chinese Fn target as pinyin or Zhuyin, check tone directions and reviewed compound-final/Unicode outputs, punctuation/control reachability, Fn indicators and return. Compare exact literal output, not Hanzi candidates. Verify editable static keys and applicable dynamic-panel controls against real touches. |

## Running

```sh
git submodule update --init
python3 scripts/prepare_rime.py
./gradlew --no-daemon test assembleDebug assembleDebugAndroidTest assembleRelease
python3 scripts/check_native_apk.py build/outputs/apk/debug/*.apk
python3 scripts/check_native_apk.py build/outputs/apk/release/*.apk --release
```

For an already running emulator, put the APKs at `artifacts/app-debug.apk` and `artifacts/app-debug-androidTest.apk`, then run `bash scripts/emulator_tests.sh`. It runs native, touch/lifecycle and process-restart phases, fails on instrumentation errors, and collects device screenshots, logs and metrics under `test-results/`.

The editor fixture is debug-only and not exported. It is absent from release APKs. Existing inherited suggestion/autocorrect/automatic-capitalisation preferences are not offered by the MVP: English is direct, while manual Shift remains supported. The U0 initializer preserves then-supported layouts/settings and assigns MIANCAK QWERTY on a fresh installation; the new first-install suite checks both entry orders. The later U3 plan deliberately retires other built-in main layouts through a separate migration and does not claim they remain available after removal.

## Screenshot evidence

Editor/DOM assertions establish input state independently of image capture. Screenshots also wait for UI idleness and two explicit UI frame callbacks, then require a successful capture; a returned commit or JavaScript value alone does not establish that the new pixels have been presented. The rotation scenario captures actual landscape in both languages and restored portrait states, and checks that language switching preserves landscape keyboard bounds. Inactive inherited English-completion targets are removed from the split gap; a geometry regression ensures the letter keys retain their original positions.

Review post-commit editor, private-field, WebView and process-restart images together with the assertions. A stale or blank image requires investigation before treating it as visual evidence.

## Current results

- M1: 14 semantic planner tests and baseline CI passed.
- M2: all declared native ABIs built; API 35 x86_64 real native tests passed, including repeat initialization and no-learning database checks (PR #13).
- Complete MVP: [CI run 39823589374](https://github.com/AuroraLilja8514/miancak/actions/runs/39823589374), source `e28bb1f4bcd0cbcf1f11b2a20703f7efed197541`, passed all five checks on 2026-10-22.
- 59 distinct unit tests execute in both debug and release variants: 118 executions, zero failures/errors. This includes 14 planner, 18 controller, 3 selection-tracker and 3 semantic-layout tests, plus inherited regression tests.
- Both API 23 and API 35 x86_64 guests passed 9 native/platform tests, the complete touch/lifecycle scenario and the separate force-stop/reopen scenario. Wi-Fi/mobile data were disabled.
- Debug and minified unsigned release APKs built for all three declared ABIs and passed package inspection. ARM devices were build-checked; runtime interaction coverage is x86_64 emulator only.
- The complete scenario verifies Chinese/ASCII punctuation, literal digits, one-finger Shift latch/lock, candidate paging/touch/cancel, composing Space/Backspace, multiline Enter, Chinese/English geometry equality, English direct output, theme recreation, cursor/field changes, password/numeric/no-learning fields, rotation and WebView.
- Fixed failures discovered by these gates: an IME-initialization migration-order case, legacy preference type handling in test setup, partial native commit loss, transient accessibility-node reads and unsigned release packaging. The settings-first/default-layout path described above was not covered by those prepared-state tests and remains open.

One measured sample from that run (50 warm composition-plus-confirmation operations per guest):

| Guest | Runtime open (ms) | Warm JNI p50 (ms) | Warm JNI p95 (ms) | Test-process PSS (KiB) |
| --- | ---: | ---: | ---: | ---: |
| API 23 / x86_64 | 1508.471 | 2.259 | 3.018 | 17204 |
| API 35 / x86_64 | 7162.228 | 2.461 | 9.381 | 67061 |

Runtime-open cost includes native initialization/maintenance in this test sequence; it is not an isolated cold-install or warm-resume benchmark. The measurements show why startup work belongs on the serialized worker. Per-run reports are observations, without a pass/fail latency target.

## Measurement limits

`native-metrics.json` reports the measured deployment verification/runtime-open cost, warm JNI composition-and-confirmation p50/p95 and test-process PSS. Those are debug/emulator observations, not physical-device touch-to-editor performance or production memory guarantees. The full UI scenario duration is also recorded, including deliberate test waits/gestures. CI logs record actual APK/dictionary bytes; the emulator report records allocated Rime-directory KiB after testing, including compiled data and test learning. These are not a production user-data size guarantee. No physical-device benchmark, release signing or store publication is claimed.

### C3 统一页面 APK 场景

真实触控从主键盘 Enter 右下打开选择器，检查拼音与编辑器未变化；从选择器进入 Fn 目标后检查 Fn 状态，再按 Fn 返回主页面。检查希腊字母实际提交、表情／剪贴板的可见选择器入口以及返回。组件层另验旧内置布局备份、自定义 XML 与选择位置保留和重复迁移幂等。以上仍需 CI 运行结果，不以已有截图或测试代码代替通过。
