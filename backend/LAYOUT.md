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
  "state": "ready",                // DumpState value; absent in legacy dirs
  "error": null,                   // last failure text when state == "failed"
  "dump": "daemon.hprof",          // hprof filename
  "classes": { "<key>": "<fqcn>" },// analyzed classes (analysis index)
  "rs": { "<key>": {...} },        // retained-set extract info
  "anatSamples": { "<key>": [32] },// which sample counts were extracted
  "...": "other analysis-index fields (see backend/mat.py _analysis_index)"
}
```

The analysis-index fields keep the old on-disk format (it is written and read
only by `MatQueryEngine`; nothing else may rely on it). Readers treat a
missing/invalid meta.json as "not ready". Legacy dirs (no `state` field) are
normalized by the one-time migration, not by the store.

## Index mtime convention (unchanged from matindex.py)

A raw `*.index` whose mtime exactly equals its `*.index.zst` sibling is
untouched since compaction and may be deleted. Any other mtime means
"modified since compaction" → re-compress. MAT must never be run against a
dump without restoring (`zstd -d`) missing indexes first.

## .dl/ download conventions (unchanged from serve.py)

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

## Migration (historical)

Pre-rewrite dump dirs were normalized into this contract once by a throwaway
script (ran 2026-08, since deleted): it derived `state` from old evidence and
wrote it into meta.json. Dirs from before that run show up as FAILED with "no
recorded state" — delete and re-download them. Future format changes get the
same treatment: a throwaway migration, never permanent compat code.
