# Rime custom configuration (unreleased 0.3.3)

Settings → Chinese word tables has separate import actions for vocabulary
`.dict.yaml` and configuration `.custom.yaml`. Vocabulary's existing data format
is unchanged. Configuration support is a bounded mobile profile of Rime's
[standard patch format](https://github.com/rime/home/wiki/CustomizationGuide),
not an arbitrary schema, script, plugin or model installer.

## Supported files and settings

Import `default.custom.yaml`, `wanxiang.custom.yaml` or
`miancak_pinyin.custom.yaml`. Each must be strict UTF-8, at most 256 KiB, with
exactly one nonempty `patch` mapping. Comments and a UTF-8 BOM are accepted and
retained in the original. Quoted slash paths and nested mappings are equivalent:

```yaml
# default.custom.yaml
patch:
  "menu/page_size": 5
```

```yaml
# wanxiang.custom.yaml
patch:
  translator:
    enable_completion: false
    enable_word_completion: false
    max_homophones: 4
  "switches/@7/reset": 1
```

| Path | Accepted values | Scope |
| --- | --- | --- |
| `menu/page_size` | Decimal integer 1–10 | All three target names; both mobile schemas |
| `translator/enable_completion` | `true` / `false` | Schema targets; both mobile schemas |
| `translator/enable_word_completion` | `true` / `false` | Schema targets; both mobile schemas |
| `translator/max_homophones` | Decimal integer 1–32 | Schema targets; both mobile schemas |
| `switches/@3/reset` | 0 / 1 | Bundled Base normal pipeline: emoji |
| `switches/@4/reset` | 0 / 1 | Bundled Base normal pipeline: English/Chinese translation |
| `switches/@5/reset` | 0 / 1 | Bundled Base normal pipeline: context reorder |
| `switches/@7/reset` | 0–3 | Bundled Base normal pipeline: Simplified / general Traditional / Hong Kong / Taiwan |

These switch indices belong to the pinned Base v18.1.13 schema; they are not a
promise about another distribution or version. Switch resets do not change the
fixed private pipeline. Normal Base Lua statistics are not disabled by changing
the context-reorder preference. Protected editors still use the app's separate
no-learning pipeline.

`default.custom.yaml` accepts only page size. At most one file per target may be
active; precedence is default, Wanxiang, then MIANCAK. Later schema settings
override earlier values. A Wanxiang target or Base switch reset cannot be applied
to an explicitly selected data-only custom/legacy base. Other common settings can.

Unknown keys fail instead of being ignored. In particular, schema IDs,
dependencies, component lists, grammar/dictionary/user-dictionary names and paths,
language/literal routing and private learning cannot be patched. Arbitrary spelling
algebra, key bindings, executable configuration, list append/delete operators,
nulls, tags, anchors, aliases, duplicate/overlapping paths and multiple documents
are unsupported. Parser event limits bound nesting and nodes before constructing
a YAML tree. `enable_sentence` and `max_homographs` belong to a different
translator; they are not claimed for the shipped script translator. Candidate
comment settings are also deferred because this frontend does not render them.

## Import, apply, disable and recovery

Import keeps an exact original and its SHA-256 in the credential-protected
catalog, outside Rime's automatic custom-file search path. It does not activate
the file or change the external document selected through Files. Up to 32 files
may be retained, including different versions of the same target.

Select files and choose Apply word tables. The existing serialized maintenance
transaction closes the engine, copies immutable bundled data, revalidates the
originals and folds only approved values into generated `__patch` mappings.
It does not copy unvalidated `.custom.yaml` into the user directory. The staged
manifest includes the modified schemas; compilation and an actual input probe
must succeed before the selection pointer changes. Previous deployments retain
their effective configuration and recovery sources. Compiled readiness also
checks schema IDs, known dictionaries, private component lists, disabled private
user learning and the private-first Base default.

Disable custom configuration rebuilds without patches while retaining imported
originals, the vocabulary selection, learning files and layout preferences. It
does not need a readable custom catalog, so corrupt catalog metadata cannot
prevent returning to an unpatched deployment. A corrupt catalog remains retained
and still needs repair before further catalog management; it is not silently
discarded. Restoring bundled vocabulary resets word-table choices and retains
applied patches. Imported files required by the active or previous recovery
selection cannot be removed. Older selection JSON without a patch field means
no patches and is read without rewriting it.

## Verification limits

Host tests exercise the production pinned YAML parser/validator, malformed and
executable inputs, effective compiled page size and Traditional output, custom
data-only bases, private non-learning and refusal of unsafe compiled policies.
Instrumentation covers the Java/native import boundary, original bytes, atomic
batch import, application/disable, earlier selection JSON, damaged source/catalog
and retained recovery. It is included in the existing guarded word-native drivers.
Compilation and host results are not Android execution. Files/SAF interaction,
screen readers, rotation and actual API 35+ input/restart/overwrite remain required.
No Android 6/API 23 or API 33 runtime is part of the current test scope.

Old learning is a separate problem; see [LEARNING_RECOVERY.md](LEARNING_RECOVERY.md).
