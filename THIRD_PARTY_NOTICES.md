# Third-Party Notices

MIANCAK is a fork of [Unexpected Keyboard](https://github.com/Julow/Unexpected-Keyboard) and preserves its Git history, copyright notices and GPL-3.0 license. The inherited [cdict](https://github.com/Julow/cdict) remains pinned by the `vendor/cdict` gitlink and retains its notices.

The native build introduces these source-built components. No prebuilt native binary is vendored. Top-level pins/archive hashes are in `native/sources.lock.json`; dependency revisions below are pinned by that librime commit. The APK includes `native-sources.json` and upstream license/notice files under `assets/third-party/`.

| Component | Source/version | License | Changes and use |
| --- | --- | --- | --- |
| librime | [rime/librime](https://github.com/rime/librime), 1.17.0, `33e78140250125871856cdc5b42ddc6a5fcd3cd4` | BSD-3-Clause | Unmodified sources; static build, no external plugins or glog logging; MIANCAK JNI wrapper |
| Boost | [boostorg/boost](https://github.com/boostorg/boost), 1.92.0 CMake archive/hash in lock file | BSL-1.0 | Unmodified selected components used by librime |
| yaml-cpp | [jbeder/yaml-cpp](https://github.com/jbeder/yaml-cpp), `2f86d13775d119edbb69af52e5f566fd65c6953b` | MIT | Unmodified static build |
| LevelDB | [google/leveldb](https://github.com/google/leveldb), `99b3c03b3284f5886f9ef9a4ef703d57373e61be` | BSD-3-Clause | Unmodified static build, tests/benchmarks disabled |
| marisa-trie | [s-yata/marisa-trie](https://github.com/s-yata/marisa-trie), `3e87d53b78e15f2f43783d5e376561a8c9722051` | BSD-2-Clause OR LGPL-2.1-or-later; BSD option selected | Unmodified static build |
| OpenCC | [BYVoid/OpenCC](https://github.com/BYVoid/OpenCC), `556ed22496d650bd0b13b6c163be9814637970ae` | Apache-2.0 | Unmodified static library required by librime; no conversion data/punctuation policy imported |
| Offline legacy pinyin dictionary | [rime/rime-pinyin-simp](https://github.com/rime/rime-pinyin-simp), `0c6861ef7420ee780270ca6d993d18d4101049d0` | Apache-2.0 | 0.3.0 offline fallback; only the import declaration is renamed; all entries/weights retained; MIANCAK schema/config separate |

OpenCC's bundled Darts and RapidJSON sources retain upstream notices; preparation copies nested license/notice files alongside those of each native dependency. Disabled upstream test/build dependencies are not claimed as shipped runtime components.

AndroidX Window/Core are inherited dependencies. AndroidX Test Runner, JUnit extension and UI Automator are instrumented-test dependencies only (Apache-2.0); JUnit remains the existing unit-test dependency. Versions are pinned in `build.gradle.kts` and obtained from configured Google/Maven repositories.

Word-table acceptance uses unmodified `cn_dicts/8105.dict.yaml` and `cn_dicts/others.dict.yaml` from [iDvel/rime-ice](https://github.com/iDvel/rime-ice) at `c398c0d4526b012cb3b306f792089abed13e0413`. Its GPL-3.0 LICENSE and the original file headers/data-source attribution are preserved under `androidTest/assets/word-tables/`. `PROVENANCE.json` records exact hashes, roles and upstream paths. These files are test assets only; they do not change the production/default vocabulary. The compatible test uses the complete 8105 table; the mixed `others` table verifies that unsupported uncoded rows produce an error rather than being silently discarded. The second compatible base is the complete, already pinned rime-pinyin-simp asset above. Synthetic supplement/recovery fixtures are MIANCAK test data.

The default Chinese word pack is the complete `cn_dicts/8105.dict.yaml` and `cn_dicts/base.dict.yaml` from [iDvel/rime-ice](https://github.com/iDvel/rime-ice/tree/da1fbe602e38f26db846fa10120ee64c2b0324c0), pinned at `da1fbe602e38f26db846fa10120ee64c2b0324c0` (GPL-3.0-only). The merged 551,875 entries retain original readings and weights; duplicate policy is first exact text/code match. No third-party scheme, Lua or OpenCC data is installed. `native/word-pack.lock.json` pins inputs; APK `word-pack/current.json` records generated hashes and selection. The original selected-source headers, attribution and LICENSE are under `assets/third-party/rime-ice/`. The quality test APK additionally contains the complete ext comparison table with its source header/license; ext is not part of the default pack. The old Apache-2.0 dictionary above remains packaged for explicit offline fallback and uses the same personal-learning directory.
