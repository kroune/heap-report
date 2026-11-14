# AGENTS.md

Viewer + MAT-index infra for the Gradle sync-OOM benchmark in
`kroune/feature-module-3000`. That repo publishes daemon heap dumps (`run-*`
releases); this repo builds Eclipse MAT indexes for them on CI (`idx-*`
releases) and serves a local UI to discover, download and analyze them.
Python-stdlib-only backend, React + TypeScript frontend built with Vite.

Design docs (read before non-trivial changes):

- **ARCHITECTURE.md** — design source of truth (microkernel: `backend/core.py`
  pins the contracts, impls plug in; change a contract → change all impls in
  the same commit).
- **backend/LAYOUT.md** — on-disk layout of `dumps/<id>/`.
- **web/CONTRACTS.md** — frontend repository, component and snapshot rules.
  Production serving/export consumes `web/dist/index.html`; build it first.

## Map

- `backend/core.py` — contracts only, no I/O.
- `backend/machine/` — per-dump state machines (`dump`/`data`/`indexes`,
  `NEW → DOWNLOADING|PARSING → DONE` + `ERROR`/`CANCELLED`), pure and
  I/O-free; the flat `DumpState` served over HTTP is a projection, never
  persisted.
- `backend/localstore/` — `FsDumpStore`: owns the machines, meta.json
  (single-writer via `read_meta`/`update_meta`), the `reconcile()` loop,
  downloads (`stages.py` executors + `transfer.py` byte-moving), compaction.
- `backend/github.py` / `backend/s3.py` — the two remotes. S3 (SeaweedFS on
  the LAN) has strict priority where it has an object; GitHub fills the rest.
- `backend/mat/` — `MatQueryEngine` (read queries + analysis jobs),
  `MatRunner` (every MAT subprocess), `db.py` (per-dump `data/analysis.db`,
  stdlib sqlite3 — CSVs are ingest-then-delete, resumability lives in `kv`
  marker rows), `reach.py`, `payloads.py` (pure).
- `backend/jobs.py` — serial queue for INDEX/ANALYZE/COMPACT (one MAT JVM at
  a time, `-Xmx10g`), pool of 2 for DOWNLOAD.
- `backend/http.py` / `kernel.py` — HTTP adapter + wiring/CLI; startup
  `reconcile_all()` and the two timers are bare reconcile kicks, the machine
  decides.
- `web/src/` — React app: `data/` repositories + TanStack Query; `app/` shell;
  `features/` tabs; `viz/` React visualization components.
- `web/viz/` — shared pure layout/scene algorithms and high-volume Canvas engine.

## Commands

- UI build: `npm ci && npm run build`; then `python3 -m backend.kernel` → http://127.0.0.1:8321/
- UI development: Python backend + `npm run dev` → http://127.0.0.1:5173/
- Snapshot: `npm run build`; `python3 -m backend.snapshot --dump <id> --out report.html`
- Trigger an index build: `gh workflow run build-indexes.yml -R kroune/heap-report
  -f source_repo=kroune/feature-module-3000 -f release_tag=run-123`
- Smoke:
  `python3 -m py_compile backend/*.py backend/mat/*.py backend/machine/*.py backend/localstore/*.py tools/*.py`
  `python3 -m unittest discover -s tests -t .`
  `npm test`
  `npm run build`
  plus PyYAML-load the workflow after editing it.

## Gotchas

- **Never run `ParseHeapDump.sh` directly** against a compacted dump — go
  through `backend/mat/` (`MatRunner` restores `.zst` indexes, pins the hprof
  mtime to the oldest index mtime, clears parse debris, and holds
  `.matindex.lock` shared). MAT's staleness check is mtime-only; skipping
  this causes concurrent reparse failures.
- A command-line `-vmargs` REPLACES `MemoryAnalyzer.ini`'s `-vmargs` (they
  never merge): every backend-spawned MAT must re-specify `-Xmx`
  (`extract.py` pins `MAT_XMX`, default 10g) next to the locale flags, or the
  JVM silently runs at the default heap (RAM/4) and big-dump parses OOM.
  CI is unaffected — it invokes `ParseHeapDump.sh` without `-vmargs`.
- In-progress machine states are untrusted: jobs are process-lifetime, never
  state. Crash recovery is `store.reconcile_all()` at kernel startup (kernel
  only — snapshot/CI stores must not reconcile). ERROR/CANCELLED components
  reset only via explicit `start_download`.
- MAT indexes are optional and lazy: READY = hprof + data bundle. CI
  publishes `data.tar.gz` first and index parts later, so "downloaded before
  indexes existed" is the norm — the poll timer picks them up, or `analyze`
  triggers a download/local parse on demand.
- GitHub release assets cap at 2 GiB — dumps ship as `daemon.hprof.gz.part-*`,
  indexes as `indexes.tar.part-*`; part order comes from the parsed index,
  never name sorting. S3 ships single objects instead (`indexes.tar.zst`,
  piped through `zstd -dc` — GNU tar doesn't auto-detect on stdin).
- Sources prefer `daemon.stripped.hprof.gz` (primitive arrays zeroed in
  place — index-compatible with the full dump); the local on-disk name stays
  `daemon.hprof`.
- Remotes never swallow errors into empty results: transport failure →
  `ApiError('upstream', 502)`; only a confirmed 404 means "we don't have it".
  Listing-only exception: stale cache / isolated source so the UI works
  offline.
- GitHub unauthenticated REST is 60 req/h/IP; listings are cached 60 s —
  `gh auth login` lifts it.
- Cross-repo trigger: the benchmark repo fires `repository_dispatch` with the
  `HEAP_REPORT_PAT` secret (GITHUB_TOKEN can't dispatch across repos).
- CI uploads to S3 must disable aws-cli CRC64 checksums (`when_required`) and
  verify with a polled HEAD size — see `tools/s3_upload.sh`. The workflow is
  serialized by one repo-wide concurrency group; keep it that way.

## Conventions

- **Never commit** `dumps/`, `.tools/`, `__pycache__/` or any `*.hprof*` /
  `*.index*` — tens of GB, all gitignored.
- Python: stdlib only, compact style. Frontend: React + TypeScript, MUI,
  TanStack Query, ECharts and React Flow; obey web/CONTRACTS.md.
- Shared logic is extracted, never copied.
- Workflow changes: validate YAML, smoke-test with a small `run-*` tag before
  a full 10g dump run.
