# Private Android 15 acceptance

Use an authorized dedicated API 35+ device. For the maintained drivers, use a hardware-accelerated guest that matches the named disposable API 35 profile. Record manual evidence separately on a physical device or newer API. See [the Android gates](ANDROID_SUPPORT.md).

## Check the inputs before installing

- Check the receipt's exact source and SHA-256 values for the split APK, instrumentation and applicable overwrite pair. Use the debug APKs for installation.
- Verify local `base-v18.1.13.zip` and `wanxiang-lts-zh-hans.gram` against [the current pins](WANXIANG_SETUP.md). Check both before installing. The older model cache uses the earlier pin.
- Set the authorized `ANDROID_SERIAL`. Use `adb -s "$ANDROID_SERIAL" shell` for `getprop ro.build.version.sdk`, `getprop ro.product.cpu.abi` and `getconf PAGESIZE`. Check Android is booted and unlocked with working package and IME services. Confirm each clean target has no earlier app install or restored data. Use synthetic inputs only.
- Keep APK identities, actual stage results and failure foregrounds local. Follow the typed `summary.json` / `heartbeat.jsonl` boundary for evidence staging.

## Verified local cleanup build

The cleanup APKs come from `e269053ee9258ddbd8762a63ad83c86b5da64ab3`. All inputs below declare minSdk 33 and targetSdk 36. The app APKs have `arm64-v8a`, `armeabi-v7a` and `x86_64` libraries. The instrumentation APK contains no native libraries and targets the debug app.

| Role | Package and version code | SHA-256 |
| --- | --- | --- |
| Split debug, clean install | `org.miancak.keyboard.debug` · 6 | `49515410432e00b255cdf1d991c11dd4c64310ae9549c8f8471cd627dee4e4a0` |
| Signed instrumentation | `org.miancak.keyboard.debug.test` · 0 | `1ed1fdee284334c91f7f2c609ac65ad6726e0a789972fc8afd241879dc7323fc` |
| Split debug, overwrite fixture | `org.miancak.keyboard.debug` · 7 | `9f7d265f432e656c1f531961b36fa339c9b7af1fe14fdeb4c4b9fd2df8ba2f4e` |
| Bundled debug baseline from `aaa22ef` | `org.miancak.keyboard.debug` · 6 | `4fd93129e03c22f9dfce89ad94ba08756902a0a6707fa3df9f05520c70e2e22f` |
| Minified unsigned release | `org.miancak.keyboard` · 6 | `5e3eec3d0db0fe673c80ae56cd2ff7b9ba84e37cf7e21b6edf04294cf2723945` |

All debug inputs share certificate SHA-256 `24ea47d2f87c43b0a8e3daaef685e4c052a267029e990d98c043a4933c8cc8c1`. Use those APKs for the device cases. The unsigned release is a static packaging check. Real Android install, input, HTTPS, force-stop and overwrite are pending.

## Clean install and prepared interaction

In a fresh private run directory, link the verified pair to `artifacts/app-debug.apk` and `artifacts/app-debug-androidTest.apk`. Set `REPO` to the checkout and `MIANCAK_WANXIANG_FIXTURE_DIR` to the verified files' directory.

```sh
export ANDROID_SERIAL MIANCAK_WANXIANG_FIXTURE_DIR
export MIANCAK_TEST_API=35 MIANCAK_TEST_DISPOSABLE_GUEST=true
MIANCAK_ENTRY_ORDER=settings-first python3 "$REPO/scripts/public_test_evidence.py" run \
  --scenario first-install -- bash "$REPO/scripts/first_install_tests.sh"
```

Use a fresh `miancak-clean` guest for each entry order. Repeat with `MIANCAK_ENTRY_ORDER=ime-first`. Each invocation covers clean installation, reopen and reboot with a changed kernel boot ID, using the ordinary UI and actual receiving editor.

On a separate fresh `miancak-interactions` guest, use the same wrapper with `--scenario interaction -- bash "$REPO/scripts/emulator_tests.sh"`. It covers held anticircle, scrolling and rotation, Settings rows, and the Project and Issues destinations without opening a browser.

On a dedicated physical device, use the ordinary Android enable/picker UI. Start in app Settings for one clean case and Android IME Settings for the other.

1. Offline, type `hello2` before setup. Check unavailable Download and reject a corrupt model. Use Files to install the verified pair. Record first candidates and commit. Check unsplit QWERTY, Sup/Sub (`²`, `₂`), `你好。` and Chinese comma.
2. Select the `你好` prefix from `nihaoshijie`. Confirm the panel stays open and editor text stays unchanged. Close it, tap Shift, and type `q` for exact `你好shijieQ`. Return to Chinese. Check scrolling, rotation, inset and label sizes, then change editor with the panel open. In the prepared edited layout, require `上转逆` for the original three gestures and `逆` once for a single held anticircle.
3. Check Settings wrapping, slider Apply/Cancel and saved summaries in both orientations, with English/Chinese and ordinary/enlarged fonts. The full N3 matrix is separate. Use `LauncherIdentityTest` for exact URL dispatch.
4. Reopen offline, then reboot and verify a changed boot ID and completed boot. Check actual text after each transition.

## Separate overwrite with an earlier installed pack

Use a separate fresh target. Pair bundled debug code 6 (asset `747939897`) with same-signer split debug code 7 (asset `763629573`). Verify the two manifests and common debug certificate.

1. Install the bundled APK. Choose a non-default split and Sup/Sub setting. Apply a synthetic dictionary and `wanxiang.custom.yaml` with `patch: {menu/page_size: 3}`. Learn a synthetic phrase. Force-stop and record preferences, YAML hashes and a non-empty learning database through read-only `run-as` in the debug sandbox.
2. Offline, run `adb -s "$ANDROID_SERIAL" install --no-incremental -r "$UPDATE_APK"`. Confirm code 7 and original hashes before new learning. Check the saved choices, earlier-pack notice, unavailable Download and real Chinese input with the old pack.
3. Cancel or reject a bad file and check old offline input again. Install the verified new pair. Check ready, actual Chinese and Shift input, original YAML and learning. Compare database bytes before new learning. Reopen and reboot offline, then check the activated exact pack needs no further transfer.

The [same-manifest fixture](WANXIANG_ACCEPTANCE.md#bundled-to-split-overwrite-fixture) belongs to its earlier matching checkpoint. The formal v0.3.2 upgrade requires the real release signer and remains a separate gate. Keep uninterrupted HTTPS transfer, low-storage recovery, ARM and verified 16 KiB runtime as separate acceptance cases.

## Force-stop during a live split-pack download

This case is pending Android 15+ execution. Use only a disposable test device. Record the source and SHA-256 of the split debug APK built with the cleanup change. Start with an independent clean install, or repeat steps 1–2 of the overwrite above with a same-signer APK that includes the change. Run rotation and in-app cancellation separately.

1. With the device online, open **Chinese input setup** and tap **Download**. Set `MODEL_SHA256` to `model.sha256` from `assets/rime/delivery.json` in the verified APK. Read the on-disk length twice while setup shows a transfer. Require `0 < first < second < 390,786,092` bytes for the current pin.

   ```sh
   adb -s "$ANDROID_SERIAL" shell "run-as org.miancak.keyboard.debug stat -c %s files/rime/.wanxiang-stage/model-${MODEL_SHA256}.part"
   ```

2. Immediately run `adb -s "$ANDROID_SERIAL" shell am force-stop org.miancak.keyboard.debug` from the host. Repeat the size check before reopening. Require a still-incomplete file and an absent `files/rime/shared-<manifest-sha256>` for the new pack. Use the SHA-256 of `assets/rime/manifest.json` in the exact APK. If the transfer finished or no growing partial was observed, record that this attempt did not exercise the interruption.
3. Disable Wi-Fi and mobile data, then reopen MIANCAK manually and open **Chinese input setup**. Wait for the setup check and confirm the partial file is gone while still offline. On a fresh install, require setup to offer Download again without showing a ready pack, and type `hello2` into the editor. On the overwrite, confirm the original synthetic YAML and learning hashes before new learning and type `你好` with the earlier pack. Use the ordinary IME picker if needed.
4. After recording the offline result, reconnect and explicitly tap **Download** or **Retry download**. The installer is expected to restart at byte zero. Check for removal of the old partial and new growth below the stopped length. Record whether restart or resume was actually observed, then check ready, exact Chinese output and offline reuse after reopening. Keep the sizes and stage outcomes local.

The installer writes under `.wanxiang-stage` and holds an exclusive lock through activation or discard. It checks the pinned files and runs a native probe before atomically moving the complete pack to `shared-<manifest-sha256>`. After process death, the offline setup check can acquire the same lock and unlink a lone scratch file named for the current model checksum and shorter than the pinned length. It leaves completed models and any other stage intact. Earlier-pack discovery uses validated `shared-…` directories, with user data stored separately. [Installer](../srcs/juloo.keyboard2/input/WanxiangInstall.java) · [Pack discovery](../srcs/juloo.keyboard2/input/RimeData.java) · [Setup states](../srcs/juloo.keyboard2/input/WanxiangSetup.java)
