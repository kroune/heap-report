# On-disk contract for the local dump store

One dump = one self-contained directory under the dumps root
(env `HEAP_REPORT_DUMPS`, default `<repo>/dumps/`). The dir name is the dump
id and matches `[\w.-]+` (no `/`, no `..`).

```
dumps/<id>/
  daemon.hprof            # the heap dump (assembled from .gz parts; the
                          #   STRIPPED variant when the source has one — the
                          #   MAT indexes are built from it — else the full
                          #   dump; the on-disk name is fixed either way)
  data/
    meta.json             # store-owned state (see below)
    analysis.db           # SQLite: every extract, ingested at landing (see below)
  *.index.zst             # compacted Eclipse MAT indexes
  .dl/                    # in-flight download parts (shared by the component
                          #   stages; removed only by the store's quiescent sweep)
  .untar/<component>/     # in-flight untar staging, per component (data/indexes
                          #   untar concurrently; absent unless interrupted)
```

## data/analysis.db — the analysis store (stdlib sqlite3)

MAT only outputs CSV, so CSV stays the LANDING + interchange format (CI
publishes it in `data.tar.gz`, `MatRunner` writes it) — but once a CSV lands
it is ingested into `analysis.db` and deleted (`backend/mat/db.py`; new-format
bundles may ship the db directly). Commits are transactional, so the file's
presence implies completeness: `files.has_data()` (the machine's data
predicate) keys on it, and a crash between the file move and the ingest is
healed by re-ingesting the landed CSVs, never by re-downloading.

Resumability lives in the db, not the filesystem: the `kv` table holds
`part:<name>` ingest markers (`part:hist`, `part:dom`, `part:rs:<key>`,
`part:idsall:<key>`, `part:anat:<key>:<K>`, `part:reach:<key>:<K>`) — a marked
part is never re-extracted. Raw tables mirror the old CSVs (hist/dom/rs/
idsall/samples/nodes/einfo/edges/edgesfull/fields/strings); derived tables
(`reach`, `sgroups`, `slinks`) are written by the reachability pass
(`backend/mat/reach.py`) at analyze time. `SCHEMA_VERSION` mismatches wipe and
recreate — the caller re-ingests/re-analyzes; no compat code.

## meta.json (written ONLY by LocalDumpStore / MatQueryEngine via the store)

```json
{
  "machine": {                     // the persisted per-dump state machine
    "wanted": true,                //   the user asked for this dump (sticky)
    "want_indexes": false,         //   an analysis asked for MAT indexes (sticky)
    "dump":    { "s": "done" },    //   per component: new|downloading|parsing|
    "data":    { "s": "done" },    //     done|error|cancelled (+ error text,
    "indexes": { "s": "done",      //     compacted flag, dump-part resume tokens
      "compacted": true }          //     as "parts": { name: size })
  },
  "idx_manifest": { "<name>": 7 }, // index-set manifest persisted at commit —
                                   //   later observations validate against THIS
  "indexes": "remote",             // how the index set was acquired (remote|local)
  "dump": "daemon.hprof",          // hprof filename
  "modules": 3010                  // DefaultScriptHandler instance count
}
```

The analysis index (analyzed classes, retained-set extracts, sample counts,
sampled ids) used to live in meta.json (`classes`/`rs`/`anatSamples`/`ids`) —
it moved into analysis.db; meta.json carries only dump-level fields now.

There is no flat `state` field: the machine is the truth and the flat
`DumpState` the API serves is a pure projection of it. A dir WITHOUT a
`machine` field adopts one inferred from artifact
observation (in-progress states are untrusted by design, so inference only
needs `wanted` + DONE promotions). A dir with neither machine nor artifacts
shows up as FAILED with "no recorded state" — delete and re-download it.

## Index mtime convention

A raw `*.index` whose mtime exactly equals its `*.index.zst` sibling is
untouched since compaction and may be deleted. Any other mtime means
"modified since compaction" → re-compress. MAT must never be run against a
dump without restoring (`zstd -d`) missing indexes first.

A `.zst` that fails decompression is corrupt (truncated download, disk rot):
the restore DELETES it and raises `CorruptIndexError`. A partial index set is
never offered to MAT (a missing root index triggers a full reparse) — the
engine drops the whole untrusted set and the store's machine re-acquires it
(remote re-download when published, else a local parse).

## .dl/ download conventions

- Parts land as `.dl/<name>`; a partially written part is `.dl/<name>.tmp`
  and is resumed via HTTP Range from its current size. `.dl/` is shared by
  the three component stages running concurrently: stages drop only their
  OWN part files — the dir itself (and stale `.untar/`) is removed solely by
  the store's quiescent sweep (all components DONE, nothing live).
- Completed parts survive failed jobs and are skipped on the next attempt —
  EXCEPT when assembly rejects size-complete parts (`AssemblyError`: tar/gzip
  exits non-zero, or the staged set fails manifest validation — an all-zeros
  part is a valid EMPTY archive to GNU tar, so only validation catches it).
  Those bytes are corrupt and are deleted so the next attempt re-downloads
  instead of re-failing identically forever.
- Assembly never writes final paths directly: `cat hprof parts | gzip -dc >
  daemon.hprof.assembling` (renamed on success; gzip verifies the stream CRC),
  and the index tar untars into `<dump>/.untar/indexes/` (staging, same fs —
  tar members are individually zstd-compressed; the tar itself is not; the
  data bundle stages in `.untar/data/` — the two untar concurrently).
- The staged set is validated against `manifest.json` (`files`: name→size,
  published by CI since the 2026-08 workflow update; older idx releases lack
  it and fall back to a presence check + tar exit status) and only then moved
  into the dump dir (`os.replace` per member, under `.matindex.lock`
  EXCLUSIVE). An interrupted untar therefore leaves partial files only inside
  `.untar/` (wiped on the next attempt) — never a truncated artifact
  masquerading as complete at its final path.
- An already-extracted set is trusted ONLY after the same manifest
  validation, on every entry (stage re-entry after a crash, lazy index
  acquisition); a set that fails validation is re-fetched, and
  size-mismatched members are deleted first (they can never become valid).
  A raw `*.index` set (local parse or a restore of valid zsts) is trusted
  as-is.
- The data bundle untars through the same staging; its `data/meta.json` is
  NOT moved over the live store-owned meta.json — its non-state fields
  (`modules`, `dump`, …) are merged via `update_meta` instead.

## dumps/.tags.json — user tags (store-owned)

Root-level sidecar `{ "<dump id>": ["tag", …] }` written only via
`store.set_tags()` (same lock discipline as meta.json: thread lock +
`.tags.lock` flock, atomic tmp+rename). It is NOT meta.json so that remote
dumps without a local dir are taggable. `delete()` drops the dump's entry.

## Migration

Format changes are handled by a one-time throwaway migration script, never by
permanent compat code in the store (the CSV → analysis.db move was
`tools/migrate_analysis_db.py`: ingest via the shared path, run the reach
pass per extraction, delete the migrated CSVs/JSON sidecars, strip the old
meta.json analysis-index fields). A dir without a `machine` field is not a
legacy format burden: the store infers a machine from artifact observation
(DONE promotions only — in-progress work simply re-enters and re-validates).
A dir with neither machine nor artifacts shows up as FAILED with
"no recorded state" — delete and re-download it.
