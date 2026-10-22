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

## Running

```sh
git submodule update --init
python3 scripts/prepare_rime.py
./gradlew --no-daemon test assembleDebug assembleDebugAndroidTest assembleRelease
python3 scripts/check_native_apk.py build/outputs/apk/debug/*.apk
python3 scripts/check_native_apk.py build/outputs/apk/release/*.apk --release
```

For an already running emulator, put the APKs at `artifacts/app-debug.apk` and `artifacts/app-debug-androidTest.apk`, then run `bash scripts/emulator_tests.sh`. It runs native, touch/lifecycle and process-restart phases, fails on instrumentation errors, and collects device screenshots, logs and metrics under `test-results/`.

The editor fixture is debug-only and not exported. It is absent from release APKs. Existing inherited suggestion/autocorrect/automatic-capitalisation preferences are not offered by the MVP: English is direct, while manual Shift remains supported. Custom layouts/settings are preserved; only a first install without a selected layout receives MIANCAK QWERTY.

## Current results

- M1: 14 semantic planner tests and baseline CI passed.
- M2: all declared native ABIs built; API 35 x86_64 real native tests passed, including repeat initialization and no-learning database checks (PR #13).
- Complete MVP: [CI run 39823589374](https://github.com/AuroraLilja8514/miancak/actions/runs/39823589374), source `e28bb1f4bcd0cbcf1f11b2a20703f7efed197541`, passed all five checks on 2026-10-22.
- 59 distinct unit tests execute in both debug and release variants: 118 executions, zero failures/errors. This includes 14 planner, 18 controller, 3 selection-tracker and 3 semantic-layout tests, plus inherited regression tests.
- Both API 23 and API 35 x86_64 guests passed 9 native/platform tests, the complete touch/lifecycle scenario and the separate force-stop/reopen scenario. Wi-Fi/mobile data were disabled.
- Debug and minified unsigned release APKs built for all three declared ABIs and passed package inspection. ARM devices were build-checked; runtime interaction coverage is x86_64 emulator only.
- The complete scenario verifies Chinese/ASCII punctuation, literal digits, one-finger Shift latch/lock, candidate paging/touch/cancel, composing Space/Backspace, multiline Enter, Chinese/English geometry equality, English direct output, theme recreation, cursor/field changes, password/numeric/no-learning fields, rotation and WebView.
- Fixed failures discovered by these gates: first-install migration ordering, legacy preference type handling in test setup, partial native commit loss, transient accessibility-node reads and unsigned release packaging.

One measured sample from that run (50 warm composition-plus-confirmation operations per guest):

| Guest | Runtime open (ms) | Warm JNI p50 (ms) | Warm JNI p95 (ms) | Test-process PSS (KiB) |
| --- | ---: | ---: | ---: | ---: |
| API 23 / x86_64 | 1508.471 | 2.259 | 3.018 | 17204 |
| API 35 / x86_64 | 7162.228 | 2.461 | 9.381 | 67061 |

Runtime-open cost includes native initialization/maintenance in this test sequence; it is not an isolated cold-install or warm-resume benchmark. The measurements show why startup work belongs on the serialized worker. Per-run reports are observations, without a pass/fail latency target.

## Measurement limits

`native-metrics.json` reports the measured deployment verification/runtime-open cost, warm JNI composition-and-confirmation p50/p95 and test-process PSS. Those are debug/emulator observations, not physical-device touch-to-editor performance or production memory guarantees. The full UI scenario duration is also recorded, including deliberate test waits/gestures. CI logs record actual APK/dictionary bytes; the emulator report records allocated Rime-directory KiB after testing, including compiled data and test learning. These are not a production user-data size guarantee. No physical-device benchmark, release signing or store publication is claimed.
