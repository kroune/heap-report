# ARCHITECTURE.md — design source of truth

This doc pins the design. When it and the code disagree, the doc wins — fix
the code (or update the doc in the same commit).

## Why (design rationale)

The failure modes this design exists to prevent all come from missing
ownership, not local mistakes:

- State derived from file existence gets re-interpreted differently by every
  module → a dump's state is explicit and persisted by the store.
- `meta.json` written from multiple threads with inconsistent locking →
  single writer, one locked read-modify-write path.
- A cache without an owner gets its invalidation forgotten → every cache has
  exactly one owner (the query engine backend-side, the data layer
  frontend-side).
- Per-endpoint, per-call-site error handling drops connections or spins
  forever → one error type, mapped uniformly at the HTTP boundary; every
  async UI path has a terminal error state.
- Features built by copy-paste drift → shared logic is extracted, never
  copied.

## Principles

1. **Microkernel.** A small `core` module pins the contracts. Everything else
   is an impl that nobody outside the impl inspects. Adding a source (S3,
   another repo) = new impl, zero changes elsewhere.
2. **Single writer.** Only `LocalDumpStore` mutates a dump's directory —
   meta.json, parts, indexes. Queries read. This replaces all ad-hoc locking.
3. **A dump is a hierarchy of state machines.** One machine per dump with
   three component sub-machines (`dump`/`data`/`indexes`), each
   `NEW → DOWNLOADING|PARSING → DONE`, plus terminal `ERROR` (needs the user)
   and `CANCELLED` (user abort). The flat `DumpState` the HTTP/frontend
   contract speaks is a pure PROJECTION of the machine, never stored.
4. **Frontend: repositories own all server interaction and all caches.** UI
   components are dumb. The experimental visualizations are isolated from the
   stable tabs.
5. **No new dependencies.** Python stays stdlib; JS uses native ES modules
   (no build step). This is a preference for minimalism, not a hard
   constraint — a genuinely essential, popular lib is acceptable.

## Backend modules

### `core` (api — no I/O, no imports of impls)

The whole contract surface. Types and interfaces only:

- `DumpId`, `DumpInfo` (name, size, state, source, error?, progress?)
- `DumpState` — the flat projection the HTTP/frontend contract speaks (see
  state machine below); derived from the machine, never persisted
- `DumpSource` — read-only discovery:
  - `init()` — lifecycle hook: build caches
  - `list() -> list[DumpInfo]`
- `LocalDumpStore(DumpSource)` — the one writable source, and the owner of
  the per-dump machines:
  - user actions: `start_download(id) -> Job` (download / retry / resume —
    idempotent), `cancel(id)`, `delete(id)`, `compact(id)`
  - the control plane: `reconcile(id)` / `reconcile_all()` — observe disk
    truth, query remotes when needed, run the pure `machine.decide()`,
    persist, execute actions as jobs
  - engine hooks: `request_indexes(id)`, `await_indexes(id, job)`,
    `note_indexes_corrupt(id)`
  - `get(id) -> DumpInfo`, `dir_of(id)` (any state; gating is the engine's),
    `read_meta`/`update_meta`, `set_tags`/`user_tags`
- `QueryEngine` — per-dump read queries (stats, trees, classes, composition,
  anatomy, compare); owns all payload caches, invalidated on state
  transitions
- `Job` — `{id, kind, dump, state: queued|running|done|failed, progress, log, error}`
  and `JobRegistry` (create, list, get). One job model for everything:
  downloads, indexing, analysis.
- Uniform error type mapped to `{error, code}` at the HTTP boundary.

### `impl/machine` (`backend/machine/`)

The per-dump state machines, pure and I/O-free: `types.py` (the states —
`Comp`/`Machine`/`Obs`/`RemoteView` — plus (de)serialization), `decide.py`
(the transition function: one reconcile pass over the three component
sub-machines, plus `validate()` which gates DONE on artifacts), `project.py`
(the flat DumpState projection). The store feeds observations in, persists
the mutated machine, and executes the returned actions as jobs. Rules:

- **In-progress states are untrusted**: re-entering one re-validates the
  on-disk artifacts (parts size-checked, partials resumed, staging rebuilt)
  and restarts the stage from there.
- **DONE is earned by artifacts**, not by the record: `validate()` demotes
  DONE states whose artifacts vanished, promotes components whose final
  artifacts are on disk (also how a meta-less dump dir adopts a machine).
- **Remote availability is an input** (`RemoteView`), never a state; an
  upstream failure means "unknown" — idle and re-query next tick, never a
  fake empty result.
- **Jobs are execution vehicles**, never state: liveness comes from the job
  registry at each pass; stage outcomes are CAS-applied (`machine_transition`
  with expected states) so a cancel/preempt that already moved the state wins.
- **Retries live at the work site** (per-part in transfer, whole-stage
  bounded attempts in stages) — `ERROR` is only for what those can't fix.

### `impl/localstore` (`backend/localstore/`)

Filesystem-backed store, a package: `files.py` (on-disk artifact predicates
+ drop helpers, no state), `compact.py` (zstd compaction, mtime convention),
`transfer.py` (download + assembly machinery: parallel part fetch with Range
resume, streaming `PartPipe`, staged untar + manifest validation),
`stages.py` (one executor per component acquisition), `store.py`
(`FsDumpStore`: meta/tags, machine persistence, the reconcile loop, CAS
transitions, user actions).

- **Explicit machine, persisted by the store** (single writer) in
  meta.json's `machine` field. A dir without it adopts one inferred from
  observation — in-progress states are untrusted anyway, so inference only
  needs `wanted` and the DONE promotions.
- Download assembly (parts | gzip -dc / tar -x), with:
  - manifest-based completeness check for index tars, re-validated on every
    entry (fixes the partial-untar-poisons-resume bug)
  - subprocess timeouts and error propagation that never masks the cause
- `delete` cancels in-flight stages first (abort flag) and waits for them
  to exit, then removes the dir
- All meta.json writes (single-writer rule), flock for cross-process safety

### `impl/github`

GitHub Releases source: discovery (`list()`), release asset enumeration,
parallel part download with Range resume, rate-limit cache. Never decides
what's "ready" locally — it only describes what exists remotely.

### `impl/s3`

S3 source (private SeaweedFS, stdlib SigV4): the same release layout as
single unsplit objects. Strictly preferred over GitHub: the store merges
per-source plans in priority order (each component from the first source
that has it), and transfer's `SourceRouter` re-resolves the source per part
attempt — probe-capable sources (`offer()`) win over the part's owner
(`owns()`), which is how an in-flight GitHub download switches to S3 when
an object appears late. Disabled cleanly (empty listing, no plans) when
`~/.aws/credentials` is missing.

### `impl/mat`

MAT engine: `LocalIndexer` (bootstrap = histogram + dominators) and the MAT
`QueryEngine` (per-class analysis). Serial executor (one MAT JVM at a time,
`-Xmx10g`). Restores compacted indexes before running.

A package (`backend/mat/`): `engine.py` (`MatQueryEngine` — queries, caches,
analyze/bootstrap orchestration), `extract.py` (`MatRunner` — the only place
MAT subprocesses exist), `parsing.py` (raw extract parsing helpers, pure),
`db.py` (the per-dump analysis store `data/analysis.db`: CSV landing ingest +
markers, readers, the precomputed anatomy payload blobs), `reach.py` (the
reachability pass → derived inclusive-retained / split-copy tables),
`payloads.py` (structures → JSON, pure and unit-tested; fold overflow is
budget-bounded so payload size can't grow with the object count).
Temp workspaces always cleaned up; MAT output streamed to job log, not
buffered whole.

**CI dependency:** `build-indexes.yml` drives `backend/ci.py`. The indexing
logic must remain callable as a library entry point from the workflow — do
not delete the capability.

### `impl/jobs`

In-memory `JobRegistry` + two executors: serial MAT queue, download pool
(default 2). Job logs capped, appended under one lock.

### `impl/http`

Thin HTTP adapter. No logic: parse → call core →
map result/error. Every endpoint returns either the payload or
`{error, code}` — no exception ever kills a handler silently.

## Dump state machine

One machine per dump, three component sub-machines — `dump` (the hprof),
`data` (the overview bundle), `indexes` (the MAT index set) — each:

```
NEW ──▶ DOWNLOADING ──▶ DONE         (remote acquisition)
NEW ──▶ PARSING     ──▶ DONE         (local MAT build: bootstrap for data,
                                      index parse for indexes)
DOWNLOADING/PARSING ──▶ ERROR        work-site retries exhausted; only an
                                     explicit retry (start_download) leaves it
DOWNLOADING/PARSING ──▶ CANCELLED    user abort; same re-entry rules as ERROR
```

The machine decides, per component, in one place (`machine.decide`):

- `dump` — remote-only (the hprof has no local build); a confirmed-absent
  run release is a real ERROR, anything else idles until a later tick.
- `data` — remote download when published (unprompted; CI ships the bundle
  minutes after the hprof), else the local MAT bootstrap, but ONLY on an
  explicit user action — nothing local starts unprompted.
- `indexes` — remote download when published (unprompted), else a local MAT
  parse on an explicit analysis request (`want_indexes`). A late remote
  publication preempts a running local parse (minutes vs tens of minutes).
  Local parse runs INLINE in the analyzing thread: the analyze job holds the
  serial MAT worker, so queueing the parse behind it would deadlock.

What the HTTP/frontend contract sees is the pure projection
(`machine.project`): dump ERROR/CANCELLED → FAILED; dump not done →
DOWNLOADING (ASSEMBLING once all parts are complete in `.dl/`); dump + data
done → READY (an indexes ERROR stays READY — the analysis surface reports
it); dump done + data missing → INDEXING; dump done + data ERROR → FAILED.

- `READY` requires: hprof present + data bundle (histogram + dominators).
  The MAT index set is NOT required: it is acquired lazily (remote download
  once published, or a local parse driven by the first analysis) without
  ever leaving `READY`.
- Event sources (user actions, kernel timers, stage completion, startup)
  never do work themselves — they adjust the machine (`wanted`, resets,
  `want_indexes`) and kick `reconcile()`. Passes are serialized per dump;
  the latest event always gets its evaluation (dirty flag).
- A crash mid-stage leaves `.dl/` parts on disk; startup `reconcile_all()`
  (kernel only — snapshot/CI stores are source-less and never reconcile)
  re-enters the in-progress stages, which re-validate and resume. That is
  normal operation, not "recovery".
- Analysis-level queries are served only in `READY`; overview queries
  (trees/classes/compare) are served in any busy state once the data bundle
  is unpacked.

## HTTP API (contract — implement this first)

```
GET    /api/dumps                      merged view: local states + remote sources
POST   /api/dumps/{id}/download        → Job (idempotent: resumes)
POST   /api/dumps/{id}/retry
POST   /api/dumps/{id}/cancel          abort in-flight stages (kept parts resume)
DELETE /api/dumps/{id}
GET    /api/dumps/{id}/stats|trees|classes
GET    /api/dumps/{id}/composition?class=…
GET    /api/dumps/{id}/anatomy?class=…&samples=…
GET    /api/compare?a=…&b=…
GET    /api/jobs                       all jobs w/ state, progress, log tail
```

Errors: `{"error": str, "code": str}` with a sensible status, from every
endpoint, always JSON.

## Frontend modules (native ES modules, no build)

- `data/http.js` — fetch wrapper; every call returns `{ok, data|error}`.
  Nothing in the UI handles a raw fetch.
- `data/dumprepo.js` — dump list, download/retry/delete, job polling.
- `data/dumpdatarepo.js` — per-dump queries; **cache keyed by dump id, lives
  here and only here** (fixes the stale-`CC` bug structurally).
- `data/inlinerepo.js` — same interface over the inlined snapshot payload
  (keeps snapshot exports working).
- `app/` — shell: tab routing + current-dump selection (the only app-level
  mutable state).
- `ui/tabs/` — classes, treemap, compare. Stable, thin, nearly done.
- `ui/jobs/` — independent job-status component reading the jobs endpoint.
- `viz/` — the experimental area, isolated from tabs:
  - `viz/common/` — shared calculation/query helpers over `DumpDataRepo`
  - each visualization = a separate module: **pure data-prep fn → viewModel,
    plus a dumb renderer** (no fetch, no globals in the renderer)
  - opened from any tab via an explicit `openViz(kind, className)` API
  - hard rule: logic shared by ≥2 viz modules lives in `viz/common/`, never
    copied

## Compatibility: one-time migration, not a permanent burden

The store contains **no** legacy-format compat code. Meta-less dirs adopt a
machine from artifact observation (that is the machine's design, not a compat
layer); anything beyond that is a one-time throwaway migration script.

Hard requirements that remain:

1. CI (`build-indexes.yml`) keeps building indexes; indexing stays callable
   as a library.
2. Snapshot export keeps working via `InlineDumpRepo`.
3. Acceptance: the smoke flow from AGENTS.md — `py_compile`, `node --check`,
   workflow YAML valid, and a real download of a small `run-*` end to end.

## Explicit non-goals / rules

- No framework, no bundler, no new runtime deps.
- No speculative parameters on core interfaces "for future sources" — the
  interface serves the real impls (local, GitHub, S3); the router's
  `offer()`/`owns()` are optional duck-typed capabilities, not contract.
- Shared logic is extracted, never copied (backend payload builders, frontend viz).
- Every async UI path renders an error state; nothing spins forever.
