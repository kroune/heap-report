# AGENTS.md

## What this repo is

The **viewer + MAT-index infra** for the Gradle sync-OOM benchmark that lives in
`kroune/feature-module-3000` (a synthetic 3000-module Android repro). That repo
produces daemon heap dumps (`run-*` releases); this repo builds Eclipse MAT indexes
for them on CI (`idx-*` releases) and ships the local UI (`serve.py`) that
discovers, downloads and analyzes them. There is no app code — it's a
Python-stdlib-only tool repo with a vanilla-JS UI.

## Layout

- `serve.py` — the local UI server (127.0.0.1:8321): Remote tab endpoints
  (`/api/remote`, `/api/remote/download`), a **serial** MAT job queue, a separate
  serial download queue, index autocompact. Dumps are self-contained dirs under
  `dumps/<tag>/` (hprof + `*.index[.zst]` + `data/`).
- `reportdata.py` — CSV→JSON data layer (histogram, dominators, retained-set
  composition, anatomy v1/v2, compare). `REPORT_ROOT` = `dumps/`
  (env `HEAP_REPORT_DUMPS` overrides).
- `analyze_dump.py` — `bootstrap()` = histogram + dominators only (the old curated
  6-class deep analysis was dropped on purpose; per-class `analyze_class()` remains
  for on-demand use). MAT runs via `ParseHeapDump.sh` headless; first query parses
  the dump when indexes are absent.
- `matindex.py` / `compact.py` — index `.zst` compaction; mtime convention: a raw
  `.index` whose mtime matches its `.zst` is untouched and can be dropped.
  **Never** run `ParseHeapDump.sh` against a compacted dump without going through
  `analyze_dump.py`/`serve.py` (they restore first) — MAT would re-parse everything.
- `ghremote.py` — release discovery/download; uses `gh api` when authenticated,
  anonymous REST otherwise (repos are public).
- `tools/get_mat.py` — pinned MAT (1.17.0.20260601) into `.tools/`;
  `MAT_HOME`/`MAT_PARSE` env overrides. Also used by CI (cached via actions/cache).
- `.github/workflows/build-indexes.yml` — the index builder (see README).

## Commands

- UI: `python3 serve.py` → http://127.0.0.1:8321/ → Remote tab.
- CLI bootstrap: `python3 analyze_dump.py <hprof> [name] [--jobs=N]`.
- Trigger an index build: `gh workflow run build-indexes.yml -R kroune/heap-report
  -f source_repo=kroune/feature-module-3000 -f release_tag=run-123`.
- No test suite — smoke = `python3 -m py_compile *.py tools/*.py`,
  `node --check js/*.js`, PyYAML-load the workflow, then a real download of a small
  run via the Remote tab.

## Gotchas (don't rediscover)

- Downloads (serve.py): a run first pulls the tiny `data.tar.gz` bundle
  (histogram + dominators + meta from CI) so the overview UI is usable in seconds,
  then all heavy parts — `daemon.hprof.gz.part-*` **and** `indexes.tar.part-*` —
  are fetched **in parallel** into `dumps/<tag>/.dl/` (`HEAP_REPORT_DL_CONN`,
  default 6; the CDN throttles per connection, so this multiplies throughput),
  then assembled locally (`cat parts | gzip -dc` / `| tar -x`). Two runs download
  concurrently (`HEAP_REPORT_DL_WORKERS`, default 2). Each part retries
  (`HEAP_REPORT_DL_RETRIES`, default 3) on stalled sockets; completed parts survive
  a failed job in `.dl/` and are **skipped on the next run**, so re-downloads only
  fetch what's missing. In CI the dump is streamed through `gzip -dc` — the 18 GB
  raw never coexists with the .gz there.
- GitHub release assets cap at 2 GiB: dumps ship as `daemon.hprof.gz.part-*`,
  indexes as `indexes.tar.part-*` (the individual `*.index.zst` files can exceed
  2 GiB, so they're tarred first — `tar -x` auto-handles the zstd'd members since
  the tar itself is uncompressed).
- The CI ships `data.tar.gz` (histogram + dominators + meta.json) as a separate
  tiny asset — serve.py downloads it first (instant overview), so per-class
  analysis is the only thing that waits for the heavy dump+indexes. Older idx
  releases without the bundle fall back to a local bootstrap after the download.
- The MAT job queue is serial because each MAT JVM can grow to the `-Xmx10g` in
  `MemoryAnalyzer.ini`; downloads run on their own small pool so a big fetch never
  blocks an analysis (or another download).
- Cross-repo trigger: the benchmark repo fires `repository_dispatch` with a PAT
  (`HEAP_REPORT_PAT` secret on `kroune/feature-module-3000`, Contents-RW on this
  repo). GITHUB_TOKEN alone cannot dispatch across repos; it *can* read the public
  source releases, so no secret is needed here.
- GitHub unauthenticated REST is rate-limited to 60 req/h/IP — serve.py caches the
  remote run list for 60 s (`HEAP_REPORT_REMOTE_TTL`); `gh auth login` lifts it.

## Conventions

- **Never commit** `dumps/`, `.tools/`, `__pycache__/` or any `*.hprof*` /
  `*.index*` analysis data — they are tens of GB and all gitignored.
- Python: stdlib only, no dependencies, no formatter config; match the existing
  style. JS: no build step, plain script tags in `template.html`.
- Workflow changes: validate YAML before pushing; smoke-test with an existing small
  `run-*` tag before trusting a full 10g dump run.
