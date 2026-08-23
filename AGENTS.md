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

- `backend/core.py` — all contracts: `DumpState` (a pure PROJECTION of the
  machine, never persisted), `DumpSource` / `RemoteDumpSource` /
  `LocalDumpStore`, `QueryEngine`, `JobRegistry`, `ApiError`, `Aborted`,
  `App`. No I/O here. Change a contract → change all impls in the same commit.
- `backend/machine/` — the per-dump state machines, pure and I/O-free:
  `types.py` (one `Machine` per dump = three component sub-machines
  `dump`/`data`/`indexes`, each `NEW → DOWNLOADING|PARSING → DONE`, plus
  terminal `ERROR` and `CANCELLED`), `decide.py` (the transition function +
  `validate()`, which gates DONE on artifacts), `project.py` (the flat
  DumpState projection for the HTTP/frontend contract).
- `backend/localstore/` — `FsDumpStore`: the one writable source and the
  owner of the machines. `store.py` — meta/tags (single-writer: meta.json
  only via `read_meta`/`update_meta`), machine persistence + the
  `reconcile()` loop (observe disk truth → query remotes when needed →
  pure `decide()` → persist → execute actions as jobs), CAS
  `machine_transition`, user actions (`start_download`/`cancel`/`delete`).
  `stages.py` — one executor per component acquisition; retries live at the
  work site (bounded `STAGE_ATTEMPTS`). `transfer.py` — the byte-moving
  machinery (Range resume, streaming `PartPipe` into gzip/tar as parts land,
  staged untar + manifest validation). `files.py` — artifact predicates/drop
  helpers, `compact.py` — zstd compaction (mtime convention).
  Analysis-level queries are READY-only, overview queries run off the data
  bundle in any busy state. `set_tags()` — user tags in the root-level
  `.tags.json` sidecar (remote dumps are taggable too).
- `backend/github.py` — `GitHubSource`: release discovery + asset streaming.
  Transport failures raise `ApiError('upstream', 502)`; only a confirmed 404
  means "we don't have it". Never swallow errors into empty results.
- `backend/mat/` — the MAT package. `engine.py` — `MatQueryEngine`: read
  queries (payload caches it owns; `_data_early` serves trees/classes/compare
  once the data bundle lands, `_data_dir` is READY-only) + on-demand MAT
  analysis jobs (`analyze` asks the store's machine for the indexes —
  `request_indexes` — and blocks in `await_indexes`: the remote prebuilt set
  downloads on the DOWNLOAD pool, a needed local parse runs INLINE in the
  analyze job itself — it holds the serial MAT worker, so queueing the parse
  behind it would deadlock) + local bootstrap (`submit_bootstrap`).
  `extract.py` — `MatRunner`:
  every MAT
  subprocess; restores compacted indexes before any MAT run (a `.zst` that
  fails decompression is corrupt → deleted → `CorruptIndexError`; `analyze`
  catches it, drops the untrusted set, hands the component back to the
  machine (`note_indexes_corrupt`) which re-acquires it — remote when
  published, local parse otherwise — and retries once: MAT never runs
  against a partial index set). `run()` returns
  None for a legitimately empty OQL result (MAT reports those as a text page
  with no CSV outputter, rc=0 — the report text is the only way to tell them
  from real query errors, which raise with that text attached); parsing treats
  the absent supplementary CSV as "no data". `parsing.py` — raw extract
  parsing helpers (pure). `db.py` — the per-dump analysis store
  `data/analysis.db` (stdlib sqlite3): MAT still outputs CSV (its report
  engine can't do anything else) and CSV stays the CI/CD interchange +
  landing format, but once a CSV lands it is ingested into the db and
  deleted; resumability lives in `kv` marker rows (`part:<name>`), not file
  presence; readers return the same structures the old CSV parsers produced
  (payloads.py untouched). The engine registers `store.on_data_files` so
  localstore stays free of any mat import. `reach.py` — the reachability
  pass at analyze time: per-class inclusive retained (DOWNWARD-oriented
  cones — back-references to ancestors don't count) + root-diversity shared
  bytes, holder-set split copies (`reach`/`sgroups`/`slinks` derived tables).
  `payloads.py` — structures → JSON payloads, pure.
- `backend/jobs.py` — `InMemoryJobRegistry`: serial queue for INDEX/ANALYZE/
  COMPACT (one MAT JVM at a time, `-Xmx10g`), pool of 2 for DOWNLOAD.
- `backend/http.py` / `kernel.py` — thin HTTP adapter (uniform
  `{error, code}` mapping) + wiring/CLI (`python3 -m backend.kernel`) +
  startup `store.reconcile_all()` (adopts dumps orphaned by a previous
  process) and the two timers (autocompact, index/data poll) — both are bare
  reconcile kicks; the machine decides what each dump needs.
- `backend/ci.py` — library entries used by the CI workflow (`bootstrap`,
  `compact`). Not for the server.
- `backend/snapshot.py` — self-contained static HTML export (inlines web/
  modules + payload; refuses style-rule violations).
- `web/` — the UI. `data/` owns all fetch + caching (per-dump-id keys);
  `app/` shell + current-dump state; `ui/dumppicker.js` dump-selection overlay
  (search + tag filters + row actions); `ui/tabs/` (classes/treemap/compare);
  `ui/jobs.js`; `viz/` isolated visualizations (pure `prepare` → dumb `render`).
- `tools/get_mat.py` — pinned MAT (1.17.0.20260601) into `.tools/`;
  `MAT_HOME`/`MAT_PARSE` env overrides. Used by `backend/mat/extract.py` and CI.

## Commands

- UI: `python3 -m backend.kernel` → http://127.0.0.1:8321/
- Snapshot: `python3 -m backend.snapshot --dump <id> --out report.html`
- One-time CSV → analysis.db migration (throwaway): `python3
  tools/migrate_analysis_db.py [--dump <id>]` (run once, server stopped).
- Trigger an index build: `gh workflow run build-indexes.yml -R kroune/heap-report
  -f source_repo=kroune/feature-module-3000 -f release_tag=run-123`.
- Smoke: `python3 -m py_compile backend/*.py backend/mat/*.py backend/machine/*.py backend/localstore/*.py tools/*.py`,
  `python3 -m unittest discover -s tests -t .`,
  `for f in $(find web -name '*.js'); do node --input-type=module --check < $f; done`,
  PyYAML-load the workflow, then a real download of a small run via the UI.

## Gotchas (don't rediscover)

- Downloads: each component (dump/data/indexes) is acquired by its own stage
  job (`backend/localstore/stages.py`), retried IN PLACE up to
  `HEAP_REPORT_STAGE_ATTEMPTS` (default 3) before the component goes ERROR —
  one network error never fails the download; per-part retries
  (`HEAP_REPORT_DL_RETRIES`) sit one level below in transfer. The tiny
  `data.tar.gz` lands independently (the overview tabs work from it within
  seconds, mid-download); heavy parts fetch in parallel
  (`HEAP_REPORT_DL_CONN`, default 6) into `dumps/<id>/.dl/` — completed parts
  are skipped on retry, `.tmp` partials resume via HTTP Range; retry backoff
  does NOT hold a connection slot. Download job progress is a dict
  (`DlProgress`): stage (download/assemble), done/total bytes, a 10 s-window
  speed + ETA, per-part states, and assembly-overlap bytes. Assembly is
  streamed: each part is piped
  into `gzip -dc` / `tar -x` in index order the moment it lands
  (`PartPipe`), so decompression overlaps the remaining download and gunzip
  runs concurrently with untar. Nothing lands at its final path directly:
  the hprof assembles into `daemon.hprof.assembling` (renamed on success;
  gzip verifies the stream CRC), the index tar and the data bundle untar
  into per-component `.untar/<component>/` staging (data and indexes untar
  CONCURRENTLY — a shared staging dir would let one wipe the other's
  half-extracted set) and move into place (atomic per file, under
  `.matindex.lock` EXCLUSIVE) only after the staged set passes manifest
  validation (`files`: name→size; older idx releases lack it → presence
  fallback + tar exit status) — an interrupted untar leaves partials only
  inside `.untar/`, never a truncated artifact masquerading as complete.
  An extracted set is trusted ONLY via that validation, never via presence
  (the manifest persisted at commit, `idx_manifest` in meta.json, is the
  trust basis for later observations);
  size-mismatched members are deleted and re-fetched. Assembly rejecting
  size-complete parts (bad gzip/tar stream, or a valid-but-wrong archive —
  all-zeros is an EMPTY tar to GNU tar) raises `AssemblyError` → the parts
  are deleted so the next attempt re-downloads them instead of re-failing
  forever. `.dl/` is shared by the concurrent component stages: stages only
  ever drop their OWN part files — the dir itself is removed solely by the
  store's quiescent sweep (all components DONE, nothing live).
- A dump's lifecycle is the machine (`backend/machine/`), persisted in
  meta.json's `machine` field; the flat `DumpState` is a projection.
  READY = hprof + data bundle; MAT indexes are OPTIONAL and lazy. A download
  that finds no idx release goes READY anyway (overview fully usable); the
  kernel poll timer (`HEAP_REPORT_IDX_POLL`, default 300 s) is a reconcile
  kick — the machine fetches the prebuilt indexes the moment CI publishes
  them (detail="indexes" — state stays READY, `.dl/` parts resume after a
  crash). If an analysis is requested first, `analyze` sets `want_indexes`
  and the machine picks: remote download when published, else a local MAT
  parse run INLINE in the analyze job. A running local parse IS preempted by
  a late remote publication (the abort flag goes up, the prebuilt set
  downloads instead — minutes vs tens of minutes).
  CI publishes `data.tar.gz` as the release first, index parts afterwards —
  so "downloaded before indexes existed" is the norm, not an edge case.
  When the data bundle is also missing the dump projects INDEXING instead —
  and NOTHING local starts unprompted: the poll fills it from the
  late-published release, and only an explicit retry (`start_download` —
  the UI's "Fetch or build data") falls back to the local MAT bootstrap when
  no source has anything yet. An interrupted local parse leaves debris
  (`daemon.lock.index`, `daemon.temp.*`) plus a PARTIAL raw `*.index` set;
  `drop_untrusted_raws()` drops that set (never a `.zst`-backed one) before
  any remote index fetch so it can't masquerade as complete.
- In-progress component states are UNTRUSTED: re-entering one (resume after
  crash, retry after ERROR/CANCELLED) re-validates the on-disk artifacts and
  resumes or restarts the stage. Jobs are process-lifetime execution
  vehicles, never state — a crash leaves in-progress machine states that
  `store.reconcile_all()` re-enters at server startup (kernel only — never
  from `init()`, snapshot/CI stores are source-less and must not reconcile).
  ERROR/CANCELLED components stay put until the user's explicit
  `start_download` resets them. `cancel()` is cooperative: the abort flag is
  polled at chunk/attempt boundaries (`core.Aborted`); stage outcomes are
  CAS-applied, so a cancel/preempt that already moved the state wins.
  `dir_of` hands out the dir in ANY state; state gating lives in the engine
  (`_data_dir` READY-only for analysis, `_data_early` busy-ok for the
  overview, bootstrap works during INDEXING).
- GitHub release assets cap at 2 GiB: dumps ship as `daemon.hprof.gz.part-*`,
  indexes as `indexes.tar.part-*` (individual `*.index.zst` can exceed 2 GiB,
  so they're tarred first). Part order comes from an explicit parsed index,
  never name sorting.
- Index mtime convention: a raw `.index` whose mtime matches its `.zst` is
  untouched and can be dropped. MAT's "is the index stale?" check is also
  mtime-only: an hprof newer than `daemon.index` triggers a full reparse.
  Re-assembled downloads stamp the hprof with "now" while pre-built indexes
  keep their CI build time, so `MatRunner._pin_hprof` pins the hprof mtime to
  the oldest index mtime and clears stale parse debris (`daemon.lock.index`,
  `daemon.temp.*`) before every run — without it, two `_par` JVMs reparse at
  once and die on "Concurrent parsing error". **Never** run
  `ParseHeapDump.sh` against a compacted dump except through `backend/mat/`
  (`MatRunner` restores first). `.matindex.lock` (flock) serializes this
  across processes: compact/restore/pin/untar-commit take it exclusive,
  every MAT run holds it shared for its whole lifetime. A `.zst` that fails
  `zstd -d` is corrupt (truncated download, disk rot): the restore deletes
  it and raises `CorruptIndexError` — a partial index set is never offered
  to MAT (it would reparse the whole dump); `analyze` drops the set and the
  machine re-acquires it (remote re-download when published, else a local
  parse).
- Kernel timers: both are bare reconcile kicks — autocompact re-compresses
  indexes when the machine sees raws over a compacted set with the MAT queue
  idle and no live compact hold; the poll downloads later-published
  data/indexes. `POST /api/dumps/<id>/compact-hold` (`{"seconds": N}`,
  N ≤ 3600, default the cap) pins a dump's restored indexes against
  autocompact for agents running query bursts (a restore is minutes of
  zstd); `DELETE` releases early. Holds are process-lifetime (`_Rt`) — a
  restart drops them and the client re-locks.
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
