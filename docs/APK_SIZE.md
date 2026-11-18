# Wanxiang APK size review (unreleased 0.3.3)

The measured unsigned universal release is **401,756,305 bytes** (401.76 MB /
383.14 MiB), SHA-256
`47ed55dab7b0f900bd2d1aed8e20c6e251849ad8c7fca2c8ea9ed7de6fa94de5`,
from application source `5c03f1ae250b17c93abfa4b8fa983e5741fa63cc`.
This is an APK/host review, not Android installation, startup or release acceptance.

## Largest contributors

Sizes below are compressed ZIP payload bytes, excluding entry headers/alignment.

| Component | APK payload bytes | Share of APK | Uncompressed bytes |
| --- | ---: | ---: | ---: |
| `wanxiang-lts-zh-hans.gram` | 360,231,071 | 89.6641% | 407,160,876 |
| Other Rime assets | 25,837,564 | 6.4312% | 71,786,848 |
| Six native libraries, all three ABIs | 14,023,560 | 3.4906% | 14,023,560 |
| Other application content | 923,350 | 0.2298% | 1,123,278 |
| Offline compatibility word pack | 546,609 | 0.1361% | 1,284,063 |
| Third-party notices/provenance | 116,716 | 0.0291% | 266,440 |

ZIP headers/alignment account for 77,435 additional bytes. The next largest
single asset is Base's `dicts/jichu.dict.yaml`: 19,127,823 compressed bytes from
51,714,491 original bytes. The three Rime libraries are 5,206,320 bytes (arm64),
5,184,888 (x86_64) and 3,592,676 (32-bit ARM); cdict supplies the other three.
The grammar dominates; trimming text, resources or notices cannot materially
address its size.

## What is actually bundled

All 80 runtime files from locked **Base v18.1.13** retain their source hashes.
There is exactly one complete grammar, with its original
`d4f64356231764ff27b79c9b0db79cd9d665013464cf5544e2fbd9a71e0f1c5a`
digest. There is no Pro, Lite or Pure distribution, second grammar, extra full
Ice vocabulary, or packaged Rime compilation cache. Upstream T9-related files
that belong to the original Base archive remain part of that pinned inventory;
their presence does not claim a T9 mobile frontend.

The APK is deliberately **not only two upstream files**. It also needs Base's
Lua/dependent dictionaries/OpenCC data, MIANCAK's three schema/default wrappers
and profile, native modules and notices. The previous data-only Pinyin dictionary
and separately named 0.3.0 import fallback remain for offline compatibility and
recovery. Each dictionary copy contributes about 539 KB compressed. They are
not another active full upstream scheme; their different names/roles and existing
contracts are retained rather than removing a fallback for a small saving.

`check_native_apk.py` now rejects duplicate/colliding ZIP paths, duplicate or
invalid manifest keys, any Rime payload outside the pinned Base/managed set,
second `.gram` models anywhere in the APK, and unexpected compatibility packs.
The existing complete byte hashes, source/model sizes, modules, all ABI targets,
release JNI/minification boundaries and ELF alignment checks remain in force.
The size report is produced only after those checks pass:

```sh
python3 scripts/check_native_apk.py build/outputs/apk/release/MIANCAK-release-unsigned.apk \
  --release --size-report /tmp/miancak-release-size.json
python3 scripts/test_apk_asset_inventory.py
```

The report records exact APK hash/bytes, compressed versus raw groups, overhead
and the 20 largest entries. It does not unpack data, rewrite/sign the APK or
create device evidence.

## Lossless compression experiments

All completed experiments restored the exact original model bytes. These are
Linux/Python native codec observations with uncontrolled I/O cache, **not Java
or Android timings**, and no experimental container is shipped.

| Model encoding | Encoded bytes | Outcome |
| --- | ---: | --- |
| Existing APK ZIP-DEFLATE | 360,231,071 | Current packaging; host decode/hash 2.44 s |
| Raw DEFLATE level 9 | 360,227,831 | Only 3,240 bytes smaller; no packager change justified |
| Plain XZ/LZMA2 preset 3 | Unavailable | Shared 180 s ZIP/XZ experiment timed out; no complete size or success claimed |
| XZ delta-4 + LZMA2 preset 0 | 384,133,732 | 23,902,661 bytes larger; rejected |
| Plain XZ/LZMA2 preset 0 | 337,745,160 | Potential saving 22,485,911 bytes; host decode/hash 12.26 s |

The last result is about 5.6% of the APK before decoder/metadata overhead. It
requires a new runtime deployment decoder, dependency/provenance checks and
bounded stream/integrity/recovery coverage. Its observed native host cost gives
no Android cost estimate. Adding that path before actual Android 15 waiting,
interruption and resource acceptance would add uncertainty to a known open
milestone. Therefore **production packaging and the APK size remain unchanged**.
No decoder dependency is added, and the incomplete higher-preset run is retained
as a timeout rather than retried with a longer deadline.

Dropping/quantizing the model, stripping Base tables/Lua, downloading data on
demand or removing an ABI would change requested features, provenance, offline
availability or the current artifact contract. Those are not size fixes in this
review. Per-device distribution changes and native extraction/compression would
also need their own installation/runtime validation; the current three-ABI and
ELF/ZIP 16 KiB requirements remain.

## Independent next milestone

P3's Wanxiang resource/input-quality and deployment-recovery work can proceed
independently of N3 settings alignment. This review advances its host/static
asset-integrity boundary. The next meaningful runtime step is the existing
[Android acceptance plan](WANXIANG_ACCEPTANCE.md): measure full first-use wait,
verify visible/accessibility feedback, interruption/repair, real input/quality and
protected-field behavior on Android 15+. Preserve the existing 180-second engine,
512 MiB PSS and JNI latency gates; the complete user wait is a separate scope.

No Android run occurred here, and no identical software-emulator attempt is
requested. Final N3 source access/integration is still required for combined
settings/release acceptance. P4's physical ARM, reboot/overwrite and genuine
signed-upgrade/backup recovery limitations remain open. This review does not
authorize public writes or establish release readiness.
