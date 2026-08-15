# On-disk contract for the local dump store

One dump = one self-contained directory under the dumps root
(env `HEAP_REPORT_DUMPS`, default `<repo>/dumps/`). The dir name is the dump
id and matches `[\w.-]+` (no `/`, no `..`).

```
dumps/<id>/
  daemon.hprof            # the heap dump (assembled from .gz parts)
  data/
    meta.json             # store-owned state + analysis index (see below)
    histogram.csv         # MAT histogram extract
    dominators.csv        # MAT dominator-tree extract
    anat/                 # per-class on-demand analysis extracts (CSV + sidecars)
  *.index.zst             # compacted Eclipse MAT indexes
  .dl/                    # in-flight download parts (absent when not downloading)
```

## meta.json (written ONLY by LocalDumpStore / MatQueryEngine via the store)

```json
{
  "state": "ready",                // DumpState value; absent only in unmigrated dirs
  "error": null,                   // last failure text when state == "failed"
  "dump": "daemon.hprof",          // hprof filename
  "classes": { "<key>": "<fqcn>" },// analyzed classes (analysis index)
  "rs": { "<key>": {...} },        // retained-set extract info
  "anatSamples": { "<key>": [32] },// which sample counts were extracted
  "...": "other analysis-index fields (see backend/mat/parsing.py _analysis_index_build)"
}
```

The analysis-index fields are written and read only by `MatQueryEngine`;
nothing else may rely on them. Readers treat a missing/invalid meta.json as
"not ready". Dirs without a `state` field are not adopted by the store —
normalize them with a throwaway migration, never with compat code.

## Index mtime convention

A raw `*.index` whose mtime exactly equals its `*.index.zst` sibling is
untouched since compaction and may be deleted. Any other mtime means
"modified since compaction" → re-compress. MAT must never be run against a
dump without restoring (`zstd -d`) missing indexes first.

## .dl/ download conventions

- Parts land as `.dl/<name>`; a partially written part is `.dl/<name>.tmp`
  and is resumed via HTTP Range from its current size.
- Completed parts survive failed jobs and are skipped on the next attempt.
- The store validates the extracted index set against `manifest.json`
  (`files`: name→size, published by CI since the 2026-08 workflow update;
  older idx releases lack it and fall back to a presence check + tar exit
  status) before marking the dump READY — the mere presence of some
  `*.index.zst` files means nothing. Index parts still sitting in `.dl/` prove
  the untar never finished and force a re-extract (untar is idempotent).
- Assembly streams: `cat hprof parts | gzip -dc > daemon.hprof`,
  `cat index parts | tar -x` (tar members are individually zstd-compressed;
  the tar itself is not).

## Migration

Format changes are handled by a one-time throwaway migration script, never by
permanent compat code in the store. Dirs without a recorded `state` show up
as FAILED with "no recorded state" — delete and re-download them.
