# heap-report — Gradle daemon heap-dump viewer

Interactive local UI for the daemon heap dumps produced by the
[feature-module-3000](https://github.com/kroune/feature-module-3000) sync-OOM
benchmark, with the expensive part moved to CI:

- The benchmark publishes every run as a `run-N` / `run-N-base|candidate` **release**
  carrying `daemon.hprof.gz` (18 GB decompressed).
- The **`build-indexes` workflow here** parses those dumps with Eclipse MAT (the
  ~15–45 min, ~20 GB step) and publishes `idx-<tag>` releases with the zstd-compressed
  index files **plus a tiny `data.tar.gz`** (histogram + dominators + meta).
- The local UI (**`serve.py`**) autodiscovers both release series in its **Remote
  tab**. A download first pulls the few-MB data bundle — the overview (classes,
  treemap, compare) is usable within seconds — then fetches the dump + indexes in
  parallel in the background for per-class drill-down analysis.

## Quick start

```bash
git clone https://github.com/kroune/heap-report && cd heap-report
python3 serve.py          # http://127.0.0.1:8321/ → Remote tab → Download
```

First use downloads Eclipse MAT (~100 MB) into `.tools/` automatically
(`tools/get_mat.py`; override the location with `MAT_HOME`, the binary with
`MAT_PARSE`). Requirements: Python 3.9+, `zstd`, ~40 GB free disk per dump
(18 GB hprof + ~11 GB compacted indexes; raw indexes restore to ~20 GB while a
query runs and are re-compressed when the UI goes idle).

## What a run looks like locally

`dumps/<tag>/` — self-contained per dump: `daemon.hprof`, MAT `*.index[.zst]`
(kept compressed at rest — see `matindex.py`/`compact.py`), and `data/` with the
CSV extracts (`histogram.csv`, `dominator_by_*.csv`, per-class analyses in
`data/anat/`).

## CLI usage (no UI)

```bash
python3 analyze_dump.py dumps/run-123/daemon.hprof run-123   # bootstrap a dump dir
python3 compact.py                                           # re-compress all indexes
python3 generate.py --data dumps/run-123/data --out /tmp/report.html  # static snapshot
```

## CI: `build-indexes.yml`

```bash
gh workflow run build-indexes.yml -R kroune/heap-report \
  -f source_repo=kroune/feature-module-3000 -f release_tag=run-123
```

Also fired automatically via `repository_dispatch` by the benchmark repo's
publish steps (needs a `HEAP_REPORT_PAT` secret there). The job downloads the
dump, runs the MAT parse (`-Xmx10g`, histogram as smoke test), adds the dominator
extracts (`data.tar.gz`), zstd-compresses the indexes with this repo's own
`compact.py`, and publishes everything as an `idx-<tag>` release. Standard
ubuntu-latest runners; several builds parallelize freely (one runner per run).
Measured on an 8g dump: ~3 min download, ~15 min parse, ~1.5 min extracts,
~1.3 min compress, ~2–4 min publish.

## Layout

| path | what |
|---|---|
| `serve.py` | local UI server: Remote discovery/downloads, serial MAT job queue, autocompact |
| `reportdata.py` | data layer: MAT CSV extracts → JSON payloads (shared by UI + snapshot export) |
| `analyze_dump.py` | bootstrap pipeline (`bootstrap()`, on-demand `analyze_class()`) |
| `matindex.py`, `compact.py` | zstd compression of MAT index files, restore-on-demand |
| `ghremote.py` | GitHub release discovery + asset streaming (gh CLI or anonymous REST) |
| `generate.py` | static self-contained HTML snapshot export |
| `template.html`, `js/` | the UI (vanilla JS, no build step) |
| `tools/get_mat.py` | pinned MAT download (1.17.0.20260601), used locally and by CI |
