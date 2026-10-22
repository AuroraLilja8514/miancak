# Native Runtime

MIANCAK builds librime directly and exposes a worker-confined JNI session. Locked inputs are in `native/sources.lock.json`; dependency commits come from that exact librime tree. No Fcitx5 runtime or executable Rime plugins are included.

## Build

Use Java 17, Python 3.12+, Android platform 36, NDK `28.0.13004108` and CMake `3.22.1`:

```sh
git submodule update --init
python3 scripts/prepare_rime.py
./gradlew --no-daemon test assembleDebug assembleDebugAndroidTest
python3 scripts/check_native_apk.py build/outputs/apk/debug/*.apk
```

Preparation downloads pinned sources into ignored `.native/`, verifies the Boost archive SHA-256 and generates APK assets in `build/generated-rime-assets/`. Run it again after `clean`. Downloads happen at build time, never in the typing path. Existing source caches can be reused; no signing secret is needed.

Declared APK ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`. A local subset can be selected with `-PmiancakAbis=x86_64`; the standard packaging check intentionally requires all declared ABIs. Shared libraries use 16 KiB maximum ELF page alignment. The inherited cdict C bridge is built by the same CMake entry point; `vendor/Android.mk` is retained for upstream provenance.

The application ID is `org.miancak.keyboard` (`org.miancak.keyboard.debug` in debug), distinct from Unexpected Keyboard. The inherited Java/resource namespace remains `juloo.keyboard2`.

## Data and sessions

The APK includes the unmodified simplified-pinyin dictionary, MIANCAK schema/default configuration and a hash manifest. The schema imports neither hidden punctuation heuristics nor desktop candidate shortcuts; frontend semantic actions control literal symbols and digits.

`RimeData.deploy` verifies/copies assets into a content-versioned credential-protected directory. Matching files retain their timestamps. User/learning data lives in a separate stable `files/rime/user` directory and is never replaced by deployment. Asset work, initialization and Rime maintenance belong to worker startup, outside the UI thread and ordinary key path.

`RimeEngine` owns a persistent session and rejects calls from another thread. A native mutex protects global initialization and multiple sessions; finalization occurs after the final session closes. Immutable results contain commit text, preedit, page-local candidates, selection and composition/page state. Text uses standard UTF-8, including supplementary Unicode characters. Logging and external executable-plugin loading are disabled. Sensitive-field routing/no-learning policy belongs to the controller.

## Verification

The existing generated/unit/debug jobs remain stable. The debug build compiles instrumented tests and checks the actual APK's ABIs, offline dictionary and provenance. `interaction-tests` installs both APKs on an independent API 35 x86_64 emulator and exercises real native composition, selection, deletion, clear, repeat initialization and asset/user-data preservation.

Build success is separate from complete IME integration. Controller ordering, editor lifetimes, gesture adaptation and touch candidate tests are subsequent gates. Emulator results do not imply physical-device coverage.

## Provenance

Build integration was informed by public CMake approaches in [Trime](https://github.com/osfans/trime/tree/develop/app/src/main/jni) and [fcitx5-android prebuilder](https://github.com/fcitx5-android/prebuilder). MIANCAK has its own JNI boundary and embeds no runtime code from those frontends. See `THIRD_PARTY_NOTICES.md` and the APK's `assets/native-sources.json`.
