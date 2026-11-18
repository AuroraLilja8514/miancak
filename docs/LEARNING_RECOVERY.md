# Existing learning and Wanxiang (unreleased 0.3.3)

The update retains `files/rime/user/pinyin_simp.userdb` and its original files.
Wanxiang uses `wanxiang.userdb`. The existing explicit custom/0.3.0 vocabulary
path still uses the old Pinyin identity. No database is renamed, replaced,
cleared or automatically merged into Wanxiang.

## Why a renamed database is not a migration

The previous schema's user keys contain untoned Pinyin syllables. Base's original
dictionary contains tone-marked codes and its own table/prism. A filename change
does not translate those codes, dictionary syllable IDs, ranking history or
deletion semantics. The existing Rime user-dictionary merge/export APIs do not
establish that translation either.

The pinned librime implementation has another relevant detail:
[`LevelDbWrapper::Open`](https://github.com/rime/librime/blob/33e78140250125871856cdc5b42ddc6a5fcd3cd4/src/rime/dict/level_db.cc)
calls LevelDB's normal `DB::Open` even for Rime's read-only mode, changing only
`create_if_missing`. Opening a database for export can therefore update its
log/manifest files. A source-original preservation guarantee requires a closed,
independent copy before opening it with these APIs. Cursors must also be released
before closing their database.

## Optional recovery in settings

Settings → Chinese word tables → Recover old Pinyin phrases offers an explicit
preview, followed by a separate confirmation. It reads only this installation's
credential-protected `pinyin_simp.userdb`; it is not an arbitrary database/archive
importer and does not run automatically during an update.

The serialized maintenance transaction pauses input, confirms any existing
composition and closes the engine before copying. A native idle guard refuses a
live session. A bounded independent snapshot is verified against the original;
another independent working copy is the only database opened by Rime. Neither
copy uses hard links or symlinks. The original and pristine snapshot are hashed
again after export and before confirmation. Copying, enumeration and output use
finite size/time bounds: 512 files / 64 MiB per database, 200,000 records and
64 MiB of output, with a 60-second preparation budget. At least 192 MiB of free
private storage is required to start; later dictionary deployment needs more.

The preview shows the source hash and active, deleted, unsupported and total
counts. Every row is accounted for. Unknown packed values, invalid UTF-8 or
unsupported codes block the entire proposed recovery; no partial output can be
activated. A verified snapshot remains after a later failure or interruption and
must be explicitly discarded before a fresh preview. If its metadata is damaged,
the settings screen still exposes explicit copy discard without clearing the
original or hiding the rest of the catalog.

Supported active phrases become a separately named, strict `.dict.yaml` using
lowercase untoned Pinyin and **`max(1, commits)` weights**. The output must pass the
existing dictionary importer with exactly the previewed active-row count. This
recovers phrase data, not equivalent learning behavior:

- Dynamic scores, recency/decay, grammar context and Lua statistics are not
  reconstructed. Candidate rankings can differ from both the old schema and
  native Wanxiang learning.
- Negative/deleted records are counted and excluded. Their tombstones remain in
  the original database; they do not suppress words in Base or another dictionary.
- Tone-marked/unknown codes and other storage formats are not silently converted.
  They require a separately validated conversion before activation is possible.

Confirmation rechecks the source, pristine snapshot, output hash and the exact
applied selection. Any intervening old learning or applied-selection change
requires a new preview. Unsaved controls on the settings screen are not applied.
The recovered dictionary is imported into the existing catalog and added as a
supplement to the applied base, extras and custom patches. An independent
deployment must compile and produce actual candidates before its selection
pointer changes. A failed deployment retains the old selection, imported recovery
source, snapshot and preview; it does not rename or replace either learning DB.
An interrupted final preview-status update cannot roll back an already committed
working selection; the catalog/selection remain authoritative.

Discard recovery copies is explicit: it removes this preview's snapshot, working
copy and output only. It leaves the original Pinyin DB, any imported recovered
dictionary and the applied selection intact. Keep the snapshot until the
recovered phrases have been checked. This snapshot is local recovery material,
not a promise of complete export or backup migration.

## Verification scope

The native host test invokes the same production exporter on synthetic old
learning made by the real earlier schema. It checks active/deleted accounting,
unsupported-record refusal, original/pristine bytes, a real recovered candidate
and private input without learning writes. JVM tests cover independent copies,
identity, links and bounds. Instrumentation covers closed-engine refusal, missing
source, explicit confirmation, preserved applied patches, damaged output and
stale-source refusal in an isolated synthetic catalog.

Host results and instrumentation compilation do not establish Android execution.
Android 15+ settings/confirmation, normal/private input, process interruption,
reopen, overwrite and reboot acceptance remain required, along with actual old
data coverage. No learning maps or snapshots belong in review uploads.
