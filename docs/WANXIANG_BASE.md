# Wanxiang Base integration (unreleased 0.3.3)

The development default uses **Wanxiang Base v18.1.13**, including its original
tone-marked dictionaries and Simplified Chinese grammar model. It does not use
Pro, Lite or Pure, and does not strip dictionary tones or recalculate weights.
This changes development inputs; earlier Rime Ice APK results do not certify it.
The Android minimum remains API 33 and runtime acceptance targets API 35+.

## Exact distribution and licenses

`native/wanxiang.lock.json` pins the official [Base release](https://github.com/amzxyz/rime-wanxiang/releases/tag/v18.1.13):

- Repository commit: `9704d41ee6e52bac3f66618a267826d360aa64ee`.
- `rime-wanxiang-base.zip`, asset `746092731`, 26,197,573 bytes,
  SHA-256 `2a94c48aa51eab2de20b4fdc89e065c05c828875b2bb3336a680f7c140ef088d`.
- The download candidate pins [LTS grammar asset 763629573](https://api.github.com/repos/amzxyz/rime-wanxiang-data/releases/assets/763629573), `wanxiang-lts-zh-hans.gram`, 390,786,092 bytes,
  SHA-256 `e9ddd501b7d536bc5ea1346f8f900ae49a719a05be349e0a17cb27e717143ca5`.
- The frozen bundled build still uses asset `747939897`, 407,160,876 bytes,
  SHA-256 `d4f64356231764ff27b79c9b0db79cd9d665013464cf5544e2fbd9a71e0f1c5a`.
  Each build checks its numeric asset ID, size and digest.

The former `amzxyz/RIME-LMDG` URL redirects to `amzxyz/rime-wanxiang-data`.
Base and model data carry CC BY 4.0 attribution. The model repository's
[license scope](https://github.com/amzxyz/rime-wanxiang-data/blob/e6033a6042390e798e2a2cbf9a6c8ea707f7d8c7/LICENSE-SCOPE.md)
does **not** grant an independent license for its original updater/tool code;
that code is not imported. The data license, scope, notice, repository/version,
original Base default and a file-by-file digest inventory are packaged under
`third-party/wanxiang`. OpenCC data and the Base API documentation retain their
respective upstream notices; a repository-level data license does not relicense
third-party code.

The existing librime 1.17.0 pin remains unchanged. New statically built inputs,
locked in `native/sources.lock.json`, are:

| Component | Revision | License |
| --- | --- | --- |
| lotem/librime-octagram | `57d18b9f58e5284bd891d559f6bdd16cf60341e9` | BSD-3-Clause |
| hchunhui/librime-lua | `6da5d6cedb853380ed1b5b9b6979cb1a9c028353` | BSD-3-Clause |
| Lua 5.4.9 from its pinned thirdparty branch | `9e5bb71db1544913f8005dadc3df8439c00d08b7` | MIT |

Preparation assembles the locked Lua sources outside the clean upstream
checkouts, without running Wanxiang's installers or model-repository tools.
One bounded librime-lua change replaces a 64-bit all-ones literal with native
`size_t` max for the zero/unlimited lookup limit, fixing 32-bit ARM compilation
without dropping that ABI or suppressing its conversion diagnostic. Before/after
source hashes are recorded in the packaged provenance; upstream checkouts remain
clean. The existing OpenCC C++17 iterator deprecation exception also applies to
the Lua adapter that includes those same pinned headers.
The frozen bundled build fetches the resources at build time. The follow-up APK
keeps the pinned code and configuration and acquires the matching data through
[Chinese input setup](WANXIANG_SETUP.md). Normal input remains offline.
External dynamic Rime-plugin discovery remains disabled. All three ABIs and
the existing 16 KiB ELF/ZIP alignment checks remain required.

## MIANCAK adaptation

The official archive and model are verified before use. Runtime files keep their
original bytes; desktop configuration, sample custom files, README/agent guide
and images are not installed as active user configuration. Base's original
`default.yaml` is retained as `wanxiang_defaults.yaml`, because the schema imports
its punctuation and key-binding presets. MIANCAK's default includes those presets
and overrides its schema list, menu size and switcher behavior. Wrappers preserve
the existing `miancak_pinyin` / `miancak_pinyin_private` component-facing IDs.

Normal learning uses the Base pipeline. The no-learning schema retains the Base
data, grammar and spelling algebra but explicitly selects only the core
speller/selector/navigator/editor, segmentors, script translator and uniquifier.
It instantiates no Base Lua statistics, context-reorder or commit-notifier writer.
Rime's schema-list order is fixed to start private: a remembered normal schema
must not initialize Lua writers before the frontend chooses an editor policy.
Learning is then enabled through the existing explicit JNI call.

The keyboard, paired layouts, gesture geometry, literal punctuation/digit routing,
candidate selection and serialized editor/controller boundaries remain MIANCAK's.
Bundling Base does not claim that every desktop shortcut or Lua command is exposed
by the mobile frontend. JNI requires a real readable Octagram database and checks
compiled dictionary/alphabet/private-policy fields; a successful schema-update
return alone does not establish a usable compiled pipeline.

The host audit compares every compiled normal processor, segmentor, translator
and filter with the pinned Base source and opens the real Octagram model. All
80 locked runtime source files and the separate model retain their exact bundled
digests. This is Base data plus its full normal pipeline, with explicit mobile
adaptations; the private core-only pipeline and selected legacy/custom data-only
bases are not advertised as full Base feature parity.

There are additional bounded frontend deviations. Upstream
`lua/wanxiang/set_schema.lua` recognizes its own `wanxiang` / Pro / Lite schema
IDs, not the retained `miancak_pinyin` IDs. Its slash-based scheme-switch commands
therefore do not perform mobile selection changes. Supported spelling selectors
instead go through the validated, transactional `.custom.yaml` interface. The
upstream replacer's cross-schema union list also names upstream IDs; arbitrary
cross-schema replacement patches are not supported or claimed as tested here.
Those upstream Lua bytes are preserved rather than rewritten or allowed to
bypass catalog deployment. The frontend does not expose a desktop Rime menu,
all shortcuts, T9 or arbitrary scheme/script installation.

## Custom YAML and existing data

The existing `.dict.yaml` importer remains data-only, with its original header,
UTF-8, code, size, dependency and atomic-switch checks. Originals and catalog IDs
are not rewritten. This dictionary interface does not import schemas, scripts or models. A separate
[custom configuration interface](RIME_CUSTOM_CONFIG.md) accepts standard named
`.custom.yaml` patches for documented mobile settings, with independent validation,
staged compilation and recovery; it does not install arbitrary external schemes.

Supplements to bundled Base enter a separate generated dictionary that imports
the untouched Wanxiang dictionary. Existing untoned user rows are not forced into
the restricted parser used for tone-marked upstream data. An explicitly selected
custom base and the existing 0.3.0 fallback keep the previous fixed data-only
schema; another Wanxiang distribution is not substituted.

`files/rime/user`, imported originals, saved preferences and learning directories
retain their paths. Wanxiang uses `wanxiang.userdb`; previous
`pinyin_simp.userdb` files remain intact. Their codes/rankings are not automatically
converted or promised to work as Wanxiang learning. Existing selected custom bases
retain their previous learning identity. No uninstall, clear-data, bulk rewrite,
destructive database conversion or unvalidated complete export is performed.
An explicit [recovered-phrases preview](LEARNING_RECOVERY.md) can add compatible
active old phrases as a separate supplement, preserving the original DB and a
verified snapshot; it does not transfer equivalent rankings or deletion semantics.

Selections compile and probe in a separate managed version before their pointer
changes. The immutable grammar can use an internal hard link, with a copy fallback;
learning and imported originals are never linked. An intact previous packaged
bundle can be selected after a failed new runtime, with an explicit recovery
message. Previous packaged bundles are retained rather than erased during this
update. Earlier four-asset bundles did not persist a manifest; recovery verifies
their exact reconstructed historical manifest against the content-addressed
directory name without rewriting them. New bundles persist their manifest only
after asset verification. A selected old deployment that cannot rebuild retains
its working files. [Deployment recovery](DEPLOYMENT_RECOVERY.md) separates package
identification from copying so an asset-preparation I/O failure can reach verified
retained data, with an explicit notice and retry using saved choices. Storage or
native failures can still prevent input; host failure checks do not establish an
Android rollback or interruption pass.

The raw grammar alone is about 388 MiB. Dictionaries, compiled data, APK storage
and any filesystem copy fallback need additional space. Hash/deployment work stays
on the initialization worker. Android cold-start time, memory and device storage
cost must be measured; model presence is not a performance result.

The [APK size review](APK_SIZE.md) measures the universal release at 401.76 MB:
the one complete grammar contributes 89.66% of its APK bytes. It records exact
Base/compatibility membership, lossless compression experiments and why no
unvalidated deployment codec or feature removal is adopted.

## Verification and remaining acceptance

`scripts/test_prepare_wanxiang.py` covers pinned release identity, source-byte
preservation, bad downloads, archive/path failures, private pipelines and required
presets. `RimeAssetPathTest` covers recursive asset paths. A host native executable
uses the actual pinned engine/plugins/model, real earlier learning, private and
normal Base schemas and missing-model refusal; host results must remain distinct
from Android/JNI/editor execution.

The original Rime Ice/legacy quality comparison and all of its assertions remain.
The same instrumentation now also measures the actual bundled Base against the
unchanged 70-case corpus: top1 >=80%, top5 >=95%, neither below baseline, PSS
<=512 MiB, cold deployment <=180 s and core JNI P95 <=200 ms. These are pending
Android gates, not claimed results. Extra quality metrics pass only through the
existing fixed numeric public summary; raw samples remain local.

Required API 35 acceptance includes first installation in both entry orders,
normal UI input/gestures, no-learning with a remembered normal schema, custom YAML
supplements/bases, custom-configuration import/disable/recovery, existing-data
overwrite, reopening/reboot, dictionary repair,
all quality/resource checks and the earlier unresolved diagnostic failures.
Physical ARM and genuine formal two-version upgrades remain separate checks.
No API 23 or API 33 guest run is part of the current scope. Version 0.3.2 history,
upstream attribution and the dated acceptance records remain preserved.

The [Android acceptance plan](WANXIANG_ACCEPTANCE.md) explicitly includes complete
first-deployment timing, visible/accessibility waiting feedback and interruption
and recovery. It keeps total user wait separate from the engine-only quality
timer and does not infer MIANCAK phone timings from another Android frontend.
