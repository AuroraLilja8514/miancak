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

## Investigated recovery path

The host-only `wanxiang_host_test ... learning-recovery` mode investigates an
optional **recovered-phrases dictionary**, rather than an equivalent learning-DB
conversion. It generates synthetic old learning using the actual old schema and
database implementation; it never accepts a real user's database as its input.

The experiment:

1. Closes every session/database, then copies the old database without hard links
   or symlinks. The prototype bounds the copy to 512 files and 64 MiB.
2. Opens only the copy and accounts for every active/deleted record. It bounds
   records/output, validates supported untoned codes, refuses unknown formats
   rather than returning a partial success, and retains original deletion marks
   in the original database.
3. Generates an independent data-only dictionary of active phrases, using
   `max(1, commits)` as an explicit experimental weight. These weights are not a
   reconstruction of Rime's dynamic scores.
4. Adds the dictionary to a separate staged Base supplement, compiles it with
   the actual grammar and confirms a recovered phrase through real candidates.
   Private input must leave every original learning file byte-for-byte unchanged.
5. Introduces an unsupported code on the copy and requires rejection of the
   whole proposed conversion, with the originals still unchanged.

This demonstrates a feasible path for compatible active phrases. It does not
prove complete recovery, identical ranking, equivalent deleted-entry behavior,
context/statistics migration, every old storage format, or Android overwrite
migration. The experiment is compiled only as a host test; it is not an app
migration command, user-facing export action or automatic update step.

Before offering it in the app, add an explicit preview/confirmation showing the
source identity, active/deleted/unsupported counts, weight policy and limitations;
validate every generated row through the existing strict dictionary importer;
preserve a verified closed snapshot; then use the existing independent deployment
and atomic selection path. A failed or cancelled operation must keep the old
selection and originals. Android 15+ normal/private input, termination/recovery,
reopen, overwrite and reboot tests remain required. No learning maps or snapshots
belong in public or private review uploads.
