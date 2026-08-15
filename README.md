# heap-report — Gradle daemon heap-dump viewer

Interactive local UI for the daemon heap dumps produced by the
[feature-module-3000](https://github.com/kroune/feature-module-3000) sync-OOM
benchmark, with the expensive part moved to CI:

- The benchmark publishes every run as a `run-N` / `run-N-base|candidate` **release**
  carrying `daemon.hprof.gz` (18 GB decompressed).
- The **`build-indexes` workflow here** parses those dumps with Eclipse MAT (the
  ~15–45 min, ~20 GB step) and publishes `idx-<tag>` releases with the zstd-compressed
  index files, a tiny `data.tar.gz` (histogram + dominators + meta), and a
  `manifest.json` with per-file sizes for integrity checks.
- The local UI autodiscovers both release series. A download first pulls the
  few-MB data bundle — the overview (classes, treemap, compare) is usable within
  seconds — then fetches the dump + indexes in parallel in the background for
  per-class drill-down analysis.

## Quick start

```bash
git clone https://github.com/kroune/heap-report && cd heap-report
python3 -m backend.kernel          # http://127.0.0.1:8321/  → pick a remote run → Download
```

First use downloads Eclipse MAT (~100 MB) into `.tools/` automatically
(`tools/get_mat.py`; override the location with `MAT_HOME`, the binary with
`MAT_PARSE`). Requirements: Python 3.9+, `zstd`, ~40 GB free disk per dump
(18 GB hprof + ~11 GB compacted indexes; raw indexes restore to ~20 GB while a
query runs and are re-compressed when the UI goes idle).

## Architecture

Microkernel: `backend/core.py` pins every contract (dump state machine,
sources, store, query engine, jobs); the impls plug into it. See
**ARCHITECTURE.md** (design), **backend/LAYOUT.md** (on-disk contract),
**web/CONTRACTS.md** (frontend modules).

- `backend/` — the server: `localstore.py` (the writable dump store, single
  writer), `github.py` (release source), `mat/` (MAT query engine package:
  engine / extract / parsing / payloads), `jobs.py` (job registry + executors),
  `http.py` (thin HTTP adapter), `kernel.py` (wiring + CLI), `ci.py` (library
  entries for the workflow), `snapshot.py` (static HTML export).
- `web/` — the UI: native ES modules, no build step. Data layer
  (`web/data/*`) owns all server interaction and caching; tabs are thin;
  experimental visualizations live isolated in `web/viz/`.

## CLI

```bash
python3 -m backend.kernel --port 8321             # the UI server (also: all analysis)
python3 -m backend.snapshot --dump run-123 --out /tmp/report.html   # static snapshot
```

## CI: `build-indexes.yml`

```bash
gh workflow run build-indexes.yml -R kroune/heap-report \
  -f source_repo=kroune/feature-module-3000 -f release_tag=run-123
```

Also fired automatically via `repository_dispatch` by the benchmark repo's
publish steps (needs a `HEAP_REPORT_PAT` secret there). The job downloads the
dump, runs the MAT parse (`-Xmx10g`, histogram as smoke test), adds the dominator
extracts (`data.tar.gz`), zstd-compresses the indexes via `backend/ci.py`, and
publishes everything as an `idx-<tag>` release. Standard ubuntu-latest runners;
several builds parallelize freely. Measured on an 8g dump: ~3 min download,
~15 min parse, ~1.5 min extracts, ~1.3 min compress, ~2–4 min publish.
