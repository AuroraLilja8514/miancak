# Native Runtime

MIANCAK builds librime directly and exposes a worker-confined JNI session. Locked inputs are in `native/sources.lock.json`; dependency commits come from that exact librime tree. Unreleased 0.3.3 statically embeds Octagram, librime-lua and Lua for the pinned Wanxiang Base distribution/model in `native/wanxiang.lock.json`. No Fcitx5 runtime or external dynamic Rime-plugin loader is included. See [WANXIANG_BASE.md](WANXIANG_BASE.md) for exact sources, adaptations and pending runtime gates.

## Build

The application and instrumentation installation floor is Android 13 / API 33; compile/target SDK stays 36. Gradle supplies this minimum as CMake `ANDROID_PLATFORM` for all three ABIs. The dormant upstream `vendor/Android.mk` SDK declaration is not the active build floor.

Use Java 17, Python 3.12+, Android platform 36, NDK `28.0.13004108` and CMake `3.22.1`:

```sh
git submodule update --init
python3 scripts/prepare_rime.py
./gradlew --no-daemon test assembleDebug assembleDebugAndroidTest
python3 scripts/check_native_apk.py build/outputs/apk/debug/*.apk
```

Preparation downloads pinned build inputs into ignored `.native/`, verifies the Boost archive SHA-256 and generates the APK assets in `build/generated-rime-delivery/`. Run it again after `clean`. For a full Wanxiang host fixture in `build/generated-rime-assets/`, pass `--with-runtime-fixture`. The follow-up APK's resource download is explicit in [Chinese input setup](WANXIANG_SETUP.md). Normal typing uses local data. Existing source caches can be reused and no signing secret is needed.

Declared APK ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`. A local subset can be selected with `-PmiancakAbis=x86_64`; the standard packaging check intentionally requires all declared ABIs. Shared libraries use 16 KiB maximum ELF page alignment; APKs also require ZIP 16 KiB alignment. These static checks do not establish runtime on a verified 16 KiB-page guest. The inherited cdict C bridge is built by the same CMake entry point; `vendor/Android.mk` is retained for upstream provenance.

The application ID is `org.miancak.keyboard` (`org.miancak.keyboard.debug` in debug), distinct from Unexpected Keyboard. The inherited Java/resource namespace remains `juloo.keyboard2`.

## Data and sessions

The development APK includes the pinned Wanxiang Base resources, full Simplified Chinese grammar, MIANCAK schema wrappers and a recursive hash manifest. Frontend semantic actions continue to control literal symbols and digits. Base schema features are bounded by that mobile frontend. The earlier simplified-pinyin source/schema remains available for explicit fallback and existing custom bases; earlier Rime Ice results remain historical.

`RimeData.deploy` verifies/copies assets into a content-versioned credential-protected directory. Matching files retain their timestamps. User/learning data lives in a separate stable `files/rime/user` directory and is never replaced by deployment. Asset work, initialization and Rime maintenance belong to worker startup, outside the UI thread and ordinary key path.

`RimeEngine` owns a persistent session and rejects calls from another thread. A native mutex protects global initialization and multiple sessions; finalization occurs after the final session closes. Immutable results contain commit text, preedit, page-local candidates, selection and composition/page state. Text uses standard UTF-8, including supplementary Unicode characters. Logging and external dynamic-plugin discovery are disabled. Sessions start private before applying editor policy; the Base private pipeline excludes Lua writers as well as disabling its user dictionary. The normal schema is selected only through the learning-policy call. Sensitive-field routing belongs to the controller. A real model load and compiled dictionary/alphabet/private-policy check precede input; failures use the retained-deployment recovery path.

## Verification

The stable generated/unit/build jobs remain in place. Debug and minified release APKs are checked for all declared ABIs, ELF alignment, hashed offline data and provenance. Release verification also checks JNI entry-point names and excludes the debug editor fixture.

`interaction-tests` installs the debug and instrumentation APKs on independent fresh API 35 x86_64 emulators. It exercises real native composition/selection/editing, partial commits, empty engine input, repeat initialization, no-learning behavior and asset/user-data preservation. Separate phases validate the actual gesture IME and process restart with guest networking disabled. See [TESTING.md](TESTING.md) for historical results and scoped measurements, and [ANDROID_SUPPORT.md](ANDROID_SUPPORT.md) for the current floor and unrun runtime scope. Emulator results do not imply physical-device coverage.

## Provenance

Build integration was informed by public CMake approaches in [Trime](https://github.com/osfans/trime/tree/develop/app/src/main/jni) and [fcitx5-android prebuilder](https://github.com/fcitx5-android/prebuilder). MIANCAK has its own JNI boundary and embeds no runtime code from those frontends. See `THIRD_PARTY_NOTICES.md` and the APK's `assets/native-sources.json`.
