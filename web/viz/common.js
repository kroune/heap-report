/* viz/common.js — the viz hub (CONTRACTS.md "Viz contract").
 *
 * Owns:
 *  - the viz registry (registerViz) and openViz(), the ONLY way a viz opens:
 *    popup host (backdrop + panel + close), loading state, error state.
 *    A viz module is {kind, prepare(repo, dumpId, className, params), render}.
 *    prepare does ALL fetching and returns a viewModel; render is dumb and may
 *    return cleanup. Refetch/kind switch/close dispose the previous renderer;
 *    close also invalidates pending prepares. Void renderers remain supported.
 *  - the popup kind switcher: a segment in the header listing every registered
 *    kind — switching visualizations never means leaving the popup and hunting
 *    for another entry point. New viz modules appear here automatically via
 *    registerViz. Kind switches reset params (re-prepare from scratch).
 *  - ctx.refetch(params): re-runs the current viz's prepare with merged params
 *    and re-renders in place — the pinned path for interactive re-slicing that
 *    needs a fetch (e.g. the anatomy sample-count picker). Refetches of the
 *    same kind+class keep the body's scroll position.
 *  - ctx.analyze(onStatus, samples?): null in INLINE mode; otherwise queues
 *    server-side analysis for the popup's class (optional sample count — a
 *    new size only runs the missing extractions), reports job progress
 *    through onStatus(text, isErr) and reopens the current viz on that
 *    extraction when the job finishes. This is what makes "not analyzed yet"
 *    a working state instead of a dead end that sends you to the classes tab.
 *  - the sample-size picker (buildSamplePicker) + the not-analyzed prompt
 *    (buildAnalyzePrompt): ONE implementation each, shared by every
 *    anatomy-based viz (anatomy/graph/flow) — switch between finished
 *    extractions in place, run 8/16/32/custom for a new one.
 *  - the shared helpers (catColor/shortClass/scaleFactor/buildSeg) — ONE
 *    implementation each; esc/fmtB/fmtN/catOf are delegated from data/http.js.
 *
 * Repo indirection: boot() calls initViz(repo, {inline}) once with the active
 * repo — the real dumpdatarepo module in API mode, makeInlineRepo(payload) in
 * snapshot mode. openViz passes it to prepare; viz modules never know which
 * mode they run in. No globals: the repo is module-local state set once at boot.
 */

import { trees, anatomy, composition } from '../data/dumpdatarepo.js';
import * as fmt from '../data/http.js';
import { pollJobs } from '../data/dumprepo.js';

let activeRepo = null;   // set once by boot() via initViz
let inlineMode = false;  // snapshot: analyze affordances are server-only

export function initViz(repo, opts = {}) {   // called by boot before any viz can open
  activeRepo = repo;
  inlineMode = !!opts.inline;
}

function currentRepo() {
  if (!activeRepo) throw new Error('viz used before initViz()');
  return activeRepo;
}

/* ============================== shared helpers ============================== */

/* esc/fmtB/fmtN/catOf: single implementation lives in data/http.js (shared with
   the tabs); delegated here so viz modules keep one import site. */
export const esc = fmt.esc;
export const fmtB = fmt.fmtB;
export const fmtN = fmt.fmtN;

const CAT_COLORS = {
  gradle: '#e8743b', agp: '#3ba272', kotlin: '#9b7ede',
  jdk: '#4a90d9', other: '#7d8590',
};

export const catColor = id => CAT_COLORS[id] || CAT_COLORS.other;

export const catOf = fmt.catOf;

export const shortClass = cls => cls.split('.').pop();

/* Sample→global extrapolation: per-instance average over K samples scaled to
   all objCount instances. */
export const scaleFactor = (objCount, K) => Math.max(1, objCount) / K;

/* Unwrap a data-layer Result or throw — a throw inside prepare() becomes the
   popup's rendered error state (openViz below). */
export function orThrow(res) {
  if (res && res.ok) return res.data;
  throw new Error((res && res.error) || `request failed (HTTP ${res && res.status})`);
}

/* The ONE segmented control. options: [{value, label, title?}]; the clicked
   button gets .on, then onPick(value) fires. Pure DOM, no fetch — callers
   decide whether onPick re-renders locally or goes through ctx.refetch. */
export function buildSeg(options, current, onPick) {
  const seg = document.createElement('div');
  seg.className = 'anatseg';
  for (const o of options) {
    const b = document.createElement('button');
    b.textContent = o.label;
    if (o.title) b.title = o.title;
    if (o.value === current) b.classList.add('on');
    b.addEventListener('click', () => {
      for (const x of seg.querySelectorAll('button')) x.classList.toggle('on', x === b);
      onPick(o.value);
    });
    seg.appendChild(b);
  }
  return seg;
}

/* ====================== sample-size picker (shared) ====================== */

export const SAMPLE_PRESETS = [8, 16, 32];
export const MAX_SAMPLES = 1024;   // the backend clamps to this

/* The ONE sample-size control for every anatomy-based viz: sizes with an
   extraction switch in place (onSwitch), sizes without one run a new analysis
   (analyze = ctx.analyze, null in INLINE mode — then only switching renders).
   `current` is the K of the displayed payload, null when nothing is analyzed
   yet (the not-analyzed prompt: every size is a run button). */
export function buildSamplePicker({ available = [], current = null, onSwitch = null, analyze = null }) {
  const wrap = document.createElement('span');
  wrap.className = 'viz-samples';
  const status = document.createElement('span');
  status.className = 'viz-anstatus';
  const onRun = analyze
    ? k => analyze((t, isErr) => {
        status.textContent = t;
        status.classList.toggle('err', !!isErr);
      }, k)
    : null;
  const seg = document.createElement('div');
  seg.className = 'anatseg';
  const mk = (k, run) => {
    const b = document.createElement('button');
    b.textContent = run ? `${k} ▸` : `${k}`;
    if (run) {
      b.classList.add('run');
      b.title = `no ${k}-sample extraction yet — run a new analysis`;
      b.addEventListener('click', () => onRun(k));
    } else {
      b.classList.toggle('on', k === current);
      b.title = `extraction over ${k} sampled instances`;
      b.addEventListener('click', () => { if (k !== current && onSwitch) onSwitch(k); });
    }
    seg.appendChild(b);
  };
  for (const k of available) mk(k, false);
  if (onRun) for (const k of SAMPLE_PRESETS) if (!available.includes(k)) mk(k, true);
  wrap.appendChild(seg);
  if (onRun) {
    const inp = document.createElement('input');
    inp.className = 'viz-sampin';
    inp.placeholder = 'custom';
    inp.spellcheck = false;
    inp.title = `custom sample count (1–${MAX_SAMPLES}) — runs a new analysis`;
    const go = () => {
      const v = inp.value.trim();
      const k = parseInt(v, 10);
      if (!/^\d+$/.test(v) || k < 1 || k > MAX_SAMPLES) {
        inp.classList.add('err');
        return;
      }
      inp.classList.remove('err');
      if (available.includes(k)) { if (k !== current && onSwitch) onSwitch(k); }
      else onRun(k);
    };
    inp.addEventListener('keydown', e => { if (e.key === 'Enter') go(); });
    inp.addEventListener('change', go);
    inp.addEventListener('input', () => inp.classList.remove('err'));
    wrap.appendChild(inp);
    wrap.appendChild(status);
  }
  return wrap;
}

/* The "not analyzed yet" state, ONE implementation for every viz: the
   explanation, then the sample picker doubling as the Analyze button (each
   size queues the analysis; ctx.analyze reopens the viz when the job lands).
   INLINE mode (ctx.analyze null) shows the caller's fallback hint instead. */
export function buildAnalyzePrompt(ctx, { cls, text, fallback }) {
  const d = document.createElement('div');
  d.className = cls;
  const msg = document.createElement('div');
  msg.textContent = text;
  d.appendChild(msg);
  if (!ctx.analyze) {
    const hint = document.createElement('div');
    hint.className = 'hint';
    hint.textContent = fallback;
    d.appendChild(hint);
    return d;
  }
  const row = document.createElement('div');
  row.className = 'viz-anrow';
  const lab = document.createElement('span');
  lab.className = 'hint';
  lab.textContent = 'analyze this class — samples:';
  row.appendChild(lab);
  row.appendChild(buildSamplePicker({ available: [], current: null, analyze: ctx.analyze }));
  d.appendChild(row);
  return d;
}

/* ============================== popup host ============================== */

let host = null;   // {back, title, sub, nav, body} — built lazily on first openViz

function ensureHost() {
  if (host) return host;
  const back = document.createElement('div');
  back.className = 'viz-back';
  const panel = document.createElement('div');
  panel.className = 'viz-panel';
  const head = document.createElement('div');
  head.className = 'viz-head';
  const tw = document.createElement('div');
  const title = document.createElement('div');
  title.className = 'viz-title';
  const sub = document.createElement('div');
  sub.className = 'viz-sub';
  const nav = document.createElement('div');
  nav.className = 'viz-nav';
  tw.appendChild(title);
  tw.appendChild(sub);
  tw.appendChild(nav);
  const x = document.createElement('button');
  x.className = 'viz-close';
  x.textContent = '×';
  x.title = 'close (Esc)';
  head.appendChild(tw);
  head.appendChild(x);
  const body = document.createElement('div');
  body.className = 'viz-body';
  panel.appendChild(head);
  panel.appendChild(body);
  back.appendChild(panel);
  document.body.appendChild(back);
  x.addEventListener('click', closeViz);
  back.addEventListener('click', e => { if (e.target === back) closeViz(); });
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && back.classList.contains('open')) closeViz();
  });
  host = { back, title, sub, nav, body };
  return host;
}

export function closeViz() {
  seq++;                 // invalidate a prepare which is still in flight
  disposeRenderer();
  last = null;
  if (host) host.back.classList.remove('open');
}

function showState(h, cls, text, spin) {
  h.body.textContent = '';
  const d = document.createElement('div');
  d.className = cls;
  if (spin) {
    const s = document.createElement('span');
    s.className = 'viz-spinner';
    d.appendChild(s);
  }
  d.appendChild(document.createTextNode(text));
  h.body.appendChild(d);
}

/* ============================== registry + openViz ============================== */

const VIZ = new Map();

export function registerViz(module) {   // called by boot for each viz module
  VIZ.set(module.kind, module);
}

let seq = 0;   // race token: only the latest open/refetch may paint
let last = null;   // {kind, dumpId, className} of the last painted viz
let rendererCleanup = null;

/* Renderers may own global listeners, animation frames and observers. The
   legacy void return remains valid; only functions are treated as cleanup. */
function disposeRenderer() {
  const cleanup = rendererCleanup;
  rendererCleanup = null;
  if (cleanup) cleanup();
}

/* The kind switcher in the popup header: one button per registered viz.
   Every entry point (classes row, treemap leaf, another viz) lands here, so
   switching visualizations is always one click — no trip back to a tab. */
function paintNav(h, kind, dumpId, className) {
  h.nav.textContent = '';
  if (!className || VIZ.size < 2) return;
  h.nav.appendChild(buildSeg(
    [...VIZ.keys()].map(k => ({ value: k, label: k })),
    kind,
    k => { if (k !== kind) openViz(k, dumpId, className); }));
}

/* ctx.analyze implementation: queue the analysis job (optional sample count —
   the backend reuses completed parts, so a new size only runs the missing
   extractions), report progress via onStatus, reopen the same viz on the new
   extraction when the job finishes. The repo method comes from the viz module
   (analyzeOp, default 'analyze' — the shapes viz queues 'shapesAnalyze'), so
   each viz's analyze affordance runs the analysis that actually feeds it. */
function makeAnalyze(kind, dumpId, className, params) {
  const op = (VIZ.get(kind) || {}).analyzeOp || 'analyze';
  return (onStatus, samples, analysisOptions = {}) => {
    const next = op === 'shapesAnalyze' ? { ...params, ...analysisOptions }
      : samples != null ? { ...params, samples } : params;
    const repo = currentRepo();
    const p = op === 'analyze'
      ? repo.analyze(dumpId, className, samples != null ? { samples } : {})
      : repo[op](dumpId, className, analysisOptions);
    Promise.resolve(p).then(r => {
      if (!r.ok) { onStatus(`analyze failed: ${r.error || 'unknown error'}`, true); return; }
      const job = r.data;
      const reopen = () => {
        currentRepo().invalidate(dumpId);   // fresh payload: new extraction + available list
        openViz(kind, dumpId, className, next);
      };
      if (!job || job.id == null || job.state === 'done') {
        reopen();
        return;
      }
      if (job.state === 'failed' || job.state === 'cancelled') {
        onStatus(`analysis ${job.state}: ${job.error || 'see the jobs panel'}`, true);
        return;
      }
      onStatus(`analysis ${job.state} — waiting for the job to finish…`);
      const stop = pollJobs(jobs => {
        const j = (jobs || []).find(x => x.id === job.id);
        if (!j) return;
        if (j.state === 'done') { stop(); reopen(); }
        else if (j.state === 'failed' || j.state === 'cancelled') {
          stop();
          onStatus(`analysis ${j.state}: ${j.error || 'see the jobs panel'}`, true);
        } else onStatus(`analysis ${j.state}…`);
      });
    });
  };
}

export async function openViz(kind, dumpId, className, params = {}) {
  const h = ensureHost();
  const my = ++seq;
  /* Capture before disposal: a renderer is allowed to clear its container. */
  const sameTarget = last && last.kind === kind && last.dumpId === dumpId
    && last.className === className;
  const keepScroll = sameTarget ? h.body.scrollTop : 0;
  disposeRenderer();
  h.title.textContent = className || kind;
  h.sub.textContent = className ? `${kind} · ${dumpId}` : dumpId;
  paintNav(h, kind, dumpId, className);
  showState(h, 'viz-loading', `loading ${kind}…`, true);
  h.back.classList.add('open');
  const mod = VIZ.get(kind);
  if (!mod) {
    showState(h, 'viz-err', `unknown viz: ${kind}`);
    return;
  }
  let vm;
  try {
    vm = await mod.prepare(currentRepo(), dumpId, className, params);
  } catch (e) {
    if (my !== seq) return;   // user moved on
    showState(h, 'viz-err', (e && e.message) || String(e));
    return;
  }
  if (my !== seq) return;
  last = { kind, dumpId, className };
  h.body.textContent = '';
  try {
    const cleanup = mod.render(h.body, vm, {
      esc, fmtB, fmtN, catColor, catOf, shortClass,
      onOpenViz: openViz,
      /* the pinned re-slice path: merge params, re-run prepare, re-render here */
      refetch: extra => openViz(kind, dumpId, className, { ...params, ...extra }),
      /* server-only affordance: null in INLINE (snapshot) mode */
      analyze: inlineMode ? null : makeAnalyze(kind, dumpId, className, params),
    });
    rendererCleanup = typeof cleanup === 'function' ? cleanup : null;
  } catch (e) {
    if (my !== seq) return;
    showState(h, 'viz-err', (e && e.message) || String(e));
    return;
  }
  if (keepScroll) h.body.scrollTop = keepScroll;
}
