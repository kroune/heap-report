# AGENTS.md

## What this repo is

The **viewer + MAT-index infra** for the Gradle sync-OOM benchmark that lives in
`kroune/feature-module-3000` (a synthetic 3000-module Android repro). That repo
produces daemon heap dumps (`run-*` releases); this repo builds Eclipse MAT indexes
for them on CI (`idx-*` releases) and ships the local UI that discovers, downloads
and analyzes them. Python-stdlib-only backend, vanilla-JS ES-module frontend, no
build step, no dependencies.

**ARCHITECTURE.md** is the design source of truth (microkernel: `backend/core.py`
pins contracts, impls plug in). **backend/LAYOUT.md** is the on-disk contract.
**web/CONTRACTS.md** pins the frontend module rules — several are HARD rules the
snapshot bundler depends on; follow them exactly when editing `web/`.

## Layout

- `backend/core.py` — all contracts: `DumpState` state machine, `DumpSource` /
  `RemoteDumpSource` / `LocalDumpStore`, `QueryEngine`, `JobRegistry`, `ApiError`,
  `App`. No I/O here. Change a contract → change all impls in the same commit.
- `backend/localstore.py` — `FsDumpStore`: the one writable source. Owns every
  mutation of `dumps/<id>/` (single-writer rule: meta.json only via
  `read_meta`/`update_meta`; queries are READY-only). Download pipeline with
  Range resume, manifest-validated index untar, `compact_dir()` (mtime
  convention).
- `backend/github.py` — `GitHubSource`: release discovery + asset streaming.
  Transport failures raise `ApiError('upstream', 502)`; only a confirmed 404
  means "we don't have it". Never swallow errors into empty results.
- `backend/mat/` — the MAT package. `engine.py` — `MatQueryEngine`: read
  queries (payload caches it owns) + on-demand MAT analysis jobs + local
  bootstrap (`submit_bootstrap`). `extract.py` — `MatRunner`: every MAT
  subprocess; restores compacted indexes before any MAT run. `parsing.py` —
  extract files (CSV/sidecars) → structures. `payloads.py` — structures →
  JSON payloads, pure.
- `backend/jobs.py` — `InMemoryJobRegistry`: serial queue for INDEX/ANALYZE/
  COMPACT (one MAT JVM at a time, `-Xmx10g`), pool of 2 for DOWNLOAD.
- `backend/http.py` / `kernel.py` — thin HTTP adapter (uniform
  `{error, code}` mapping) + wiring/CLI (`python3 -m backend.kernel`).
- `backend/ci.py` — library entries used by the CI workflow (`bootstrap`,
  `compact`). Not for the server.
- `backend/snapshot.py` — self-contained static HTML export (inlines web/
  modules + payload; refuses style-rule violations).
- `web/` — the UI. `data/` owns all fetch + caching (per-dump-id keys);
  `app/` shell + current-dump state; `ui/tabs/` (classes/treemap/compare);
  `ui/jobs.js`; `viz/` isolated visualizations (pure `prepare` → dumb `render`).
- `tools/get_mat.py` — pinned MAT (1.17.0.20260601) into `.tools/`;
  `MAT_HOME`/`MAT_PARSE` env overrides. Used by `backend/mat/extract.py` and CI.

## Commands

- UI: `python3 -m backend.kernel` → http://127.0.0.1:8321/
- Snapshot: `python3 -m backend.snapshot --dump <id> --out report.html`
- Trigger an index build: `gh workflow run build-indexes.yml -R kroune/heap-report
  -f source_repo=kroune/feature-module-3000 -f release_tag=run-123`.
- Smoke: `python3 -m py_compile backend/*.py backend/mat/*.py tools/*.py`,
  `python3 -m unittest discover -s tests -t .`,
  `for f in $(find web -name '*.js'); do node --input-type=module --check < $f; done`,
  PyYAML-load the workflow, then a real download of a small run via the UI.

## Gotchas (don't rediscover)

- Downloads: tiny `data.tar.gz` first (instant overview), then all heavy parts
  in parallel (`HEAP_REPORT_DL_CONN`, default 6) into `dumps/<id>/.dl/` —
  completed parts are skipped on retry, `.tmp` partials resume via HTTP Range.
  Assembly streams through `gzip -dc` / `tar -x` with timeouts. The extracted
  index set is validated against the release `manifest.json` (`files`:
  name→size) before the dump may go READY; parts still in `.dl/` force a
  re-untar (it is idempotent). Older idx releases lack `files` → presence
  fallback + tar exit status.
- A dump is a state machine (`core.DumpState`); the store persists `state` in
  meta.json. Crash mid-download/assembly just means resuming later — the state
  machine + `.dl/` contents are the recovery. Queries are served only in READY
  (`dir_of` also hands out INDEXING dirs to the bootstrap job only).
- GitHub release assets cap at 2 GiB: dumps ship as `daemon.hprof.gz.part-*`,
  indexes as `indexes.tar.part-*` (individual `*.index.zst` can exceed 2 GiB,
  so they're tarred first). Part order comes from an explicit parsed index,
  never name sorting.
- Index mtime convention: a raw `.index` whose mtime matches its `.zst` is
  untouched and can be dropped. **Never** run `ParseHeapDump.sh` against a
  compacted dump except through `backend/mat/` (`MatRunner` restores first).
- Autocompact (kernel timer) re-compresses indexes when the MAT queue is idle.
- GitHub unauthenticated REST is rate-limited to 60 req/h/IP — the source
  caches listings for 60 s; `gh auth login` lifts it.
- Cross-repo trigger: the benchmark repo fires `repository_dispatch` with a PAT
  (`HEAP_REPORT_PAT` secret on `kroune/feature-module-3000`, Contents-RW on
  this repo). GITHUB_TOKEN cannot dispatch across repos; it *can* read the
  public source releases.

## Conventions

- **Never commit** `dumps/`, `.tools/`, `__pycache__/` or any `*.hprof*` /
  `*.index*` analysis data — tens of GB, all gitignored.
- Python: stdlib only, match the existing compact style. JS: native ES modules,
  no build step; obey web/CONTRACTS.md (named/namespace relative imports only,
  no top-level side effects, `esc()` everything data-derived, every async path
  has a terminal error state).
- Shared logic is extracted, never copied.
- Workflow changes: validate YAML before pushing; smoke-test with a small
  `run-*` tag before trusting a full 10g dump run.
