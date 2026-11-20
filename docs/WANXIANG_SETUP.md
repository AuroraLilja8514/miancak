# Wanxiang setup after installation

The follow-up development APK keeps MIANCAK's engine, all pinned Wanxiang Lua and runtime configuration, the small compatibility vocabulary, and the original licenses. Its first-use download supplies the remaining Wanxiang Base data and the Simplified Chinese grammar. The exact installed manifest and paths match the earlier bundled Wanxiang build. This guide covers the follow-up source. The separately frozen v0.3.3 still has the full Base and grammar in its APK.

## Set up Chinese input

Open **Chinese input setup** from the launcher or MIANCAK Settings. The screen checks whether the matching resources are already installed. If they are missing, **Download** starts an explicit 433,358,449-byte transfer. English and literal input are available before setup. After the resource check and first compilation complete, use the keyboard language key to switch to Chinese. Ordinary typing and later restarts use local files.

You can cancel the transfer or leave the screen and return to complete setup. The screen shows separate transfer, unpacking and compilation states. A retry starts a new transfer. An interrupted install never exposes incomplete resources to the engine. Keep sufficient free storage for the verified resources, the temporary Base ZIP and the compilation reserve. Initial Android setup and model load timings still need measurement.

If the exact transfer is unavailable, **Install from verified files** uses the standard Android file picker. Select the original Base ZIP and then the matching `.gram`. Both files still have to pass the same size, SHA-256 and archive checks. MIANCAK verifies a real candidate through the offline engine using a disposable user directory before activation. Existing imported YAML, selection history, keyboard layouts and learning remain in their original locations. A damaged pack at the same identity is preserved when repaired. Data from older vocabulary can remain active through the existing recovery route.

| Resource | Pinned source | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| Wanxiang Base v18.1.13 | [`amzxyz/rime-wanxiang` asset 746092731](https://api.github.com/repos/amzxyz/rime-wanxiang/releases/assets/746092731) | 26,197,573 | `2a94c48aa51eab2de20b4fdc89e065c05c828875b2bb3336a680f7c140ef088d` |
| `wanxiang-lts-zh-hans.gram` | [`amzxyz/rime-wanxiang-data` asset 747939897](https://api.github.com/repos/amzxyz/rime-wanxiang-data/releases/assets/747939897) | 407,160,876 | `d4f64356231764ff27b79c9b0db79cd9d665013464cf5544e2fbd9a71e0f1c5a` |

The app uses GitHub's numeric asset endpoints and checks the pinned bytes even if GitHub redirects to its release CDN. These requests contain the asset identifiers and ordinary HTTPS headers. Typing, configuration and learning data stay on the device. On 2026-11-20 the Base URL returned the expected ZIP prefix and the exact model asset returned HTTP 404. The model currently listed under LTS has a different size and digest. Online setup still needs an authorized source for the pinned bytes. Attribution and CC BY 4.0 notices remain in the APK. [GitHub release asset API](https://docs.github.com/en/rest/releases/assets#get-a-release-asset)

## Verification

`python3 scripts/prepare_rime.py` generates the APK asset directory without fetching the model. `--with-runtime-fixture` additionally verifies and stages the exact model for existing host tests. `scripts/check_native_apk.py` checks both the APK's retained executable/configuration assets and the locked download plan. Set `MIANCAK_WANXIANG_FIXTURE_DIR` to a directory holding the verified `base-v18.1.13.zip` and `wanxiang-lts-zh-hans.gram` for guarded offline Android testing. The local build cache is the default directory. The driver copies these public inputs to Downloads only after the disposable-guest guard. The fixtures and any guest logs, screenshots or user data stay outside published evidence.

On Android 15+, independently exercise both unused-device entry orders. Verify direct typing with network disabled before setup, normal file-picker setup, visible waiting, actual candidates and punctuation, partial selection followed by Shift, and offline reopening and reboot. Exercise the online transfer, disconnection, cancellation, corrupt file and storage exhaustion separately. Test an in-place upgrade with intact matching bundled assets and confirm that setup offers no transfer. Verify layout, custom YAML and learning retention before and after upgrade. The genuine signed release gate still needs an approved fixture route for the split package. Host and APK checks do not establish any of these device outcomes. See [Wanxiang acceptance](WANXIANG_ACCEPTANCE.md) and [Android support](ANDROID_SUPPORT.md).
