# web/ — frontend contracts

The UI. Native ES modules, no build step, no framework, no dependencies.
Talks ONLY to the backend API (`backend/http.py`, see ARCHITECTURE.md).

## Module map (each file has exactly one owner)

```
web/index.html          shell page: dump selector, tab bar, containers, boot call   (shell)
web/app.css             shell + shared styles                                        (shell)
web/tabs.css            styles for the three tabs                                   (tabs)
web/viz.css             styles for viz popups                                       (viz)
web/graph.css           styles for the graph viz                                    (viz-graph)
web/data/http.js        fetch wrapper — the ONLY place fetch is called              (data)
web/data/dumprepo.js    dump list + lifecycle ops + job polling                     (data)
web/data/dumpdatarepo.js per-dump queries; owns ALL caching                         (data)
web/data/inlinerepo.js  same interface as dumpdatarepo over a snapshot payload      (data)
web/app/state.js        current-dump selection — the only app-level mutable state   (shell)
web/app/boot.js         boot(): wires state, tabs, jobs; exported, called by index  (shell)
web/ui/jobs.js          job status component                                        (jobs)
web/ui/tabs/classes.js  classes tab                                                 (tabs)
web/ui/tabs/treemap.js  treemap tab                                                 (tabs)
web/ui/tabs/compare.js  compare tab                                                 (tabs)
web/viz/common.js       viz registry + popup host + openViz() + shared helpers      (viz)
web/viz/anatomy.js      anatomy v1+v2 (one module, version param)                   (viz)
web/viz/hierarchy.js    hierarchy viz                                               (viz)
web/viz/graph.js        reference graph viz (layout split from rendering)           (viz-graph)
```

## Module style (HARD rules — the snapshot bundler depends on them)

1. Only **named imports** (`import {a, b}`) or **namespace imports**
   (`import * as x`) from relative sibling paths (`./x.js`,
   `../data/http.js`). No default exports, no re-exports, no dynamic imports,
   no named-import aliases (`{a as b}`). Namespace imports exist for the
   multi-`mount`/`prepare` wiring (boot, tabs) — prefer named imports
   otherwise.
2. No top-level side effects: modules export functions/constants; nothing runs
   at import time. `boot()` is the single entry, called from a
   `<script type="module">` in index.html. index.html links exactly these
   stylesheets: app.css, tabs.css, viz.css, graph.css.
3. No globals, no `window.*` — cross-module access only via imports.
4. Renderers never fetch: UI modules receive data as arguments.
5. Inline `style=` attributes only for dynamic layout values (left/top/width/
   height); everything else is CSS classes in the owning .css file.
6. DOM building: `document.createElement`/`textContent` for anything
   data-derived; innerHTML only with fully-escaped static templates. Provide
   and use one `esc()` helper (in http.js or a shared util) that escapes
   `&<>"'` — all five.

## Data layer interfaces (pinned signatures)

```js
// data/http.js
api(path)                  -> Promise<{ok:true, data} | {ok:false, status, code, error, data?}>
apiPost(path, body)        // same result shape; error body's fields are spread in
apiDel(path)               // 204 -> {ok:true, data:null}
// NEVER throws for HTTP errors; only network failure throws... no — network
// failure also returns {ok:false, status:0, code:'network', error}. Nothing
// in the UI try/catches the data layer.

// data/dumprepo.js — dump list + lifecycle + jobs
listDumps()                -> Result<[DumpInfo]>   // GET /api/dumps (merged)
startDownload(id)          -> Result<Job>          // POST /api/dumps/{id}/download
retryDownload(id)          -> Result<Job>          // POST .../retry
deleteDump(id)             -> Result<null>         // DELETE /api/dumps/{id}
pollJobs(onJobs, ms=2500)  -> stopFn               // polls GET /api/jobs; onJobs([Job])
// DumpInfo = {id, state:'remote'|'downloading'|'assembling'|'indexing'|'ready'|'failed',
//             source, size, error, progress:{done,total}|null, meta}
// Job = {id, kind, dump, detail, state:'queued'|'running'|'done'|'failed',
//        progress:{done,total}|null, log:[str], error}

// data/dumpdatarepo.js — per-dump queries; cache keyed by dump id lives HERE
// and only here (switching dumps can never serve stale data: the key IS the id).
trees(id)                                  -> Result<{stats, trees}>
classes(id, {filter='', sort='-s', page=0})-> Result<{rows,total,page,pages}>
composition(id, className)                 -> Result<payload>; 404 -> {ok:false, status:404, data:{analyzed:false}}
anatomy(id, className, {version=1, samples=null}) -> Result<payload|same 404>
compare(aId, bId)                          -> Result<payload>
analyze(id, className, {samples=32, anatomy=true}) -> Result<Job>
invalidate(id=null)                        // drop one dump's or all cached entries

// data/inlinerepo.js — same functions, same result shapes, reading the
// inlined snapshot payload (window.__INLINE__). Ops that need the server
// (analyze, downloads) return {ok:false, code:'snapshot', error:'static snapshot'}.
makeInlineRepo(payload) -> {trees, classes, composition, anatomy, compare, analyze, invalidate}
```

## App shell

```js
// app/state.js
getDump()            -> string|null
setDump(id)          // notifies listeners; no-op when unchanged
onDumpChange(fn)     -> unsubscribe

// app/boot.js
boot()               // reads config: API mode (default) or INLINE mode when
                     // window.__INLINE__ is set (snapshot); wires everything
```

Tab modules export `mount(container, repo, opts)` (`repo` = the dumpdatarepo
implementation — HTTP or inline; `opts.inline` gates server-only affordances)
and react to `onDumpChange`. They show
explicit states for: no dump selected, dump not ready (`state` message),
loading, error (from Result — never a silent spinner), and data.

## Jobs component (`ui/jobs.js`)

`mountJobs(container)` — self-contained: subscribes via `pollJobs`, renders
every job kind uniformly (kind, dump, state, progress bar from
progress.done/total, log tail expandable, error in red). Rebuilds DOM at most
once per poll tick.

## Viz contract

```js
// each viz module:
export const kind = 'anatomy' | 'hierarchy' | 'graph'
export async function prepare(repo, dumpId, className) -> viewModel
//   ^ pure data step: fetches via the passed dumpdatarepo, computes everything
export function render(container, viewModel, ctx) -> void
//   ^ dumb: no fetch, no data-repo imports, no mutation of shared state.
//     ctx = {esc, fmtB, fmtN, catColor, onOpenViz} (shared helpers from viz/common)

// viz/common.js
openViz(kind, dumpId, className)   // the ONLY way a viz opens; owns the popup
                                   // container, loading + error states
registerViz(module)                // called by boot for each viz module
initViz(repo)                      // called by boot once with the active repo
                                   // (HTTP dumpdatarepo or inline); no globals
// shared helpers exported for prepares: extrapolation factor, class-name
// shortening, category colors — ONE implementation each, here. Byte/number
// formatting, esc() and catOf live in data/http.js (shared with the tabs);
// viz/common.js delegates them into ctx.
```

Any tab may open any viz via `ctx.onOpenViz`/`openViz` (e.g. a button per row
in the classes tab). New viz = new module + one `registerViz` line.

## Snapshot mode

`window.__INLINE__` set => boot uses `makeInlineRepo(window.__INLINE__)`
instead of the HTTP dumpdatarepo, and hides server-only UI (downloads,
analyze buttons, jobs panel). The snapshot generator (owned by the jobs agent:
`backend/snapshot.py`) inlines all web/ modules into one classic script —
this is why the module-style rules are hard rules.

## Error handling rule (everywhere)

Every async UI path ends in one of: rendered data, rendered error box
(message from Result.error), or rendered "not analyzed / not ready" state.
Spinners always have a terminal state.
