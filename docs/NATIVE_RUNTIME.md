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

`RimeEngine` owns a persistent session and rejects calls from another thread. A native mutex protects global initialization and multiple sessions; finalization occurs after the final session closes. Immutable results contain commit text, preedit, page-local candidates, selection and composition/page state. Text uses standard UTF-8, including supplementary Unicode characters. Logging and external executable-plugin loading are disabled. Sessions start with a private schema whose translator disables the user dictionary. Learning is enabled only by deliberately selecting the normal schema; no-learning fields retain the private schema. Sensitive-field routing belongs to the controller.

## Verification

The stable generated/unit/build jobs remain in place. Debug and minified release APKs are checked for all declared ABIs, ELF alignment, hashed offline data and provenance. Release verification also checks JNI entry-point names and excludes the debug editor fixture.

`interaction-tests` installs the debug and instrumentation APKs on independent API 23 and API 35 x86_64 emulators. It exercises real native composition/selection/editing, partial commits, empty engine input, repeat initialization, no-learning behavior and asset/user-data preservation. Separate phases validate the actual gesture IME and process restart with guest networking disabled. See [TESTING.md](TESTING.md) for the complete integration results and scoped measurements. Emulator results do not imply physical-device coverage.

## Provenance

Build integration was informed by public CMake approaches in [Trime](https://github.com/osfans/trime/tree/develop/app/src/main/jni) and [fcitx5-android prebuilder](https://github.com/fcitx5-android/prebuilder). MIANCAK has its own JNI boundary and embeds no runtime code from those frontends. See `THIRD_PARTY_NOTICES.md` and the APK's `assets/native-sources.json`.
