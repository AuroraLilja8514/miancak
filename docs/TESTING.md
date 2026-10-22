# MVP Testing

The initial offline Chinese-input implementation follows `INTERACTION_CONTRACT.md`. CI artifacts contain unit XML reports, emulator logs/screenshots and scoped native latency measurements. Device images run on independent GitHub-hosted runners; no user environment is required.

## Verification layers

| Layer | Coverage | Execution |
| --- | --- | --- |
| Generated data | Layout/Compose/method outputs stay current | `generated` |
| Pure semantic rules | Chinese/English routing, literals, queued ordering, editor generations, stale candidates, failures, cursor acknowledgements and shutdown | Local JDK 17 + `unit` |
| Layout declarations | Explicit Engine/Literal identity, custom symbols and modifier behavior | `unit` |
| Native package | Declared arm64-v8a/armeabi-v7a/x86_64 libraries, actual 16 KiB ELF alignment, full dictionary and provenance | `build-debug` + `check_native_apk.py` |
| Real engine | Chinese candidates/selection, confirmation, backspace, clear, reinitialization, private no-learning schema, data/user preservation | Emulator instrumentation |
| Actual IME | Physical taps/swipes, exact punctuation/digits, Shift latch/lock, direct English, candidate touch, field/cursor changes, theme/view recreation, rotation, protected fields and WebView | Emulator instrumentation |
| Process restart | Force-stop then reopen the IME and compose again without reinstalling | Emulator instrumentation |

The instrumented matrix targets API 23 and API 35, x86_64. Each run disables Android guest Wi-Fi/mobile data before testing. Native tests run before UI tests so their isolated initialization/error/learning results do not share a live IME session.

## Running

```sh
git submodule update --init
python3 scripts/prepare_rime.py
./gradlew --no-daemon test assembleDebug assembleDebugAndroidTest
python3 scripts/check_native_apk.py build/outputs/apk/debug/*.apk
```

For an already running emulator, put the APKs at `artifacts/app-debug.apk` and `artifacts/app-debug-androidTest.apk`, then run `bash scripts/emulator_tests.sh`. It runs native, touch/lifecycle and process-restart phases, fails on instrumentation errors, and collects device screenshots, logs and metrics under `test-results/`.

The editor fixture is debug-only and not exported. It is absent from release APKs. Existing inherited suggestion/autocorrect/automatic-capitalisation preferences are not offered by the MVP: English is direct, while manual Shift remains supported. Custom layouts/settings are preserved; only a first install without a selected layout receives MIANCAK QWERTY.

## Current results

- M1: 14 semantic planner tests and baseline CI passed.
- M2: all declared native ABIs built; API 35 x86_64 real native tests passed, including repeat initialization and no-learning database checks (PR #13).
- M3/M4: 34 local pure-Java tests passed; integrated Android/layout/emulator verification is in progress on the integration PR. Final results must replace this line after CI.

## Measurement limits

`native-metrics.json` reports the measured deployment verification/runtime-open cost, warm JNI composition-and-confirmation p50/p95 and test-process PSS. Those are debug/emulator observations, not physical-device touch-to-editor performance or production memory guarantees. The full UI scenario duration is also recorded, including deliberate test waits/gestures. No physical-device benchmark, release signing or store publication is claimed.
