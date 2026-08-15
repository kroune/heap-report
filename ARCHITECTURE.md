# ARCHITECTURE.md — target design (rewrite spec)

This is the target architecture for the rewrite. The current code (`serve.py`,
`reportdata.py`, `analyze_dump.py`, `ghremote.py`, `js/*`) is the *reference
implementation* to mine for behavior — not a structure to preserve. When this
doc and the old code disagree, this doc wins.

## Why (root causes being fixed)

The audit found most bugs come from missing ownership, not local mistakes:

- A dump's state is implicitly encoded in which files exist; every module
  re-interprets that encoding differently (partial-untar poisons resume,
  `_assemble` masks errors).
- `meta.json` is written from multiple threads with inconsistent locking.
- Caches (Python mtime cache, JS `CC`) have no owner, so invalidation is
  forgotten (stale data after dump switch).
- Error handling is per-endpoint and per-call-site, so failures drop
  connections or spin forever.
- v1/v2 features were built by copy-paste and have already drifted.

## Principles

1. **Microkernel.** A small `core` module pins the contracts. Everything else
   is an impl that nobody outside the impl inspects. Adding a source (S3,
   another repo) = new impl, zero changes elsewhere.
2. **Single writer.** Only `LocalDumpStore` mutates a dump's directory —
   meta.json, parts, indexes. Queries read. This replaces all ad-hoc locking.
3. **A dump is a state machine**, including failure states. Nothing derives
   state from file existence except the store's recovery path, in one place.
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
- `DumpState` — see state machine below
- `DumpSource` — read-only discovery:
  - `init()` — lifecycle hook: build caches, recover state
  - `list() -> list[DumpInfo]`
- `LocalDumpStore(DumpSource)` — the one writable source:
  - `start_download(id) -> Job`, `delete(id)`, `compact(id)`
  - `get(id) -> DumpHandle` (path + state, for the query engine)
- `Indexer` — produce derived data (histogram + dominators + MAT indexes):
  - `RemoteIndexer` (fetch prebuilt from a source), `LocalIndexer` (run MAT)
- `QueryEngine` — per-dump read queries (stats, trees, classes, composition,
  anatomy v1/v2, compare); owns all payload caches, invalidated on state
  transitions
- `Job` — `{id, kind, dump, state: queued|running|done|failed, progress, log, error}`
  and `JobRegistry` (create, list, get). One job model for everything:
  downloads, indexing, analysis.
- Uniform error type mapped to `{error, code}` at the HTTP boundary.

### `impl/localstore`

Filesystem-backed store. Owns:

- **Explicit state, persisted by the store** (single writer). On-disk layout
  is normalized once by `migrate.py`; `init()` then just reads state — there
  is no legacy-adoption or forensic recovery code in the store.
- Download assembly (cat parts | gzip -dc / tar -x), with:
  - `manifest.json`-based completeness check for index tars (fixes the
    partial-untar-poisons-resume bug)
  - subprocess timeouts and error propagation that never masks the cause
    (fixes the `_assemble` `UnboundLocalError`/hang)
- `delete`, `compact` (negotiates with state machine: not while querying)
- All meta.json writes (single-writer rule), flock for cross-process safety

### `impl/github`

GitHub Releases source: discovery (`list()`), release asset enumeration,
parallel part download with Range resume, rate-limit cache. Never decides
what's "ready" locally — it only describes what exists remotely.

### `impl/mat`

MAT engine: `LocalIndexer` (bootstrap = histogram + dominators) and the MAT
`QueryEngine` (per-class analysis). Serial executor (one MAT JVM at a time,
`-Xmx10g`). Restores compacted indexes before running (matindex logic moves
here). Temp workspaces always cleaned up; MAT output streamed to job log, not
buffered whole.

**CI dependency:** `build-indexes.yml` imports `analyze_dump` as a module.
The indexing logic must remain callable as a library entry point from the
workflow (update the workflow import if the module moves — do not delete the
capability).

### `impl/jobs`

In-memory `JobRegistry` + two executors: serial MAT queue, download pool
(default 2). Job logs capped, appended under one lock.

### `impl/http`

Thin HTTP adapter (today's `serve.py` role). No logic: parse → call core →
map result/error. Every endpoint returns either the payload or
`{error, code}` — no exception ever kills a handler silently.

## Dump state machine

```
        (remote only)          ┌─────────────┐
            ─ ─ ─ ─ ─ ─ ─ ─ ─▶ │  DOWNLOADING │◀── resume (keeps .dl parts)
                               └──────┬──────┘
                                      ▼
                                ASSEMBLING ──fail──▶ FAILED(reason) ──retry──▶ DOWNLOADING
                                      ▼
        indexes absent → INDEXING (LocalIndexer fallback)
                                      ▼
                                   READY ──▶ (compact, delete allowed)
```

- `READY` requires: hprof present + meta.json valid + index completeness per
  manifest (or recorded local bootstrap).
- Transitions are job-driven and go through the store; queries are only
  served in `READY`.
- A crash mid-download leaves `.dl/` parts on disk; the next `start_download`
  resumes them (that is normal operation, not "recovery").

## HTTP API (contract — implement this first)

```
GET    /api/dumps                      merged view: local states + remote sources
POST   /api/dumps/{id}/download        → Job (idempotent: resumes)
POST   /api/dumps/{id}/retry
DELETE /api/dumps/{id}
GET    /api/dumps/{id}/stats|trees|classes
GET    /api/dumps/{id}/composition?class=…
GET    /api/dumps/{id}/anatomy?class=…&v=1|2
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
  (replaces today's `if(!API)` branches; keeps `generate.py` snapshots working).
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
    copied. (The v1/v2 anatomy drift is the cautionary tale.)

## Compatibility: one-time migration, not a permanent burden

Compatibility with the pre-rewrite `dumps/` layout was handled by a one-time
migration script (ran 2026-08 on the existing dumps, then deleted — the store
contains **no** legacy-adoption code). Future format changes get the same
treatment: a throwaway migration, never permanent compat layers.

Hard requirements that remain:

1. CI (`build-indexes.yml`) keeps building indexes; indexing stays callable
   as a library.
2. Snapshot export keeps working via `InlineDumpRepo`.
3. Acceptance: the smoke flow from AGENTS.md — `py_compile`, `node --check`,
   workflow YAML valid, and a real download of a small `run-*` end to end.

## Explicit non-goals / rules

- No framework, no bundler, no new runtime deps.
- No speculative parameters on core interfaces "for future sources" — the
  interface serves the two real impls (local, GitHub).
- Shared logic is extracted, never copied (backend v1/v2 pairs, frontend viz).
- Every async UI path renders an error state; nothing spins forever.
