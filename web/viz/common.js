/* viz/common.js — the viz hub (CONTRACTS.md "Viz contract").
 *
 * Owns:
 *  - the viz registry (registerViz) and openViz(), the ONLY way a viz opens:
 *    popup host (backdrop + panel + close), loading state, error state.
 *    A viz module is {kind, prepare(repo, dumpId, className, params), render}.
 *    prepare does ALL fetching and returns a viewModel; render is dumb.
 *  - the popup kind switcher: a segment in the header listing every registered
 *    kind — switching visualizations never means leaving the popup and hunting
 *    for another entry point. New viz modules appear here automatically via
 *    registerViz. Kind switches reset params (re-prepare from scratch).
 *  - ctx.refetch(params): re-runs the current viz's prepare with merged params
 *    and re-renders in place — the pinned path for interactive re-slicing that
 *    needs a fetch (e.g. the anatomy sample-count picker). Refetches of the
 *    same kind+class keep the body's scroll position.
 *  - ctx.analyze(onStatus): null in INLINE mode; otherwise queues server-side
 *    analysis for the popup's class, reports job progress through
 *    onStatus(text, isErr) and reopens the current viz when the job finishes.
 *    This is what makes "not analyzed yet" a working state instead of a dead
 *    end that sends you to the classes tab.
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

/* ctx.analyze implementation: queue the analysis job, report progress via
   onStatus, reopen the same viz (same params) when the job finishes. */
function makeAnalyze(kind, dumpId, className, params) {
  return (onStatus) => {
    currentRepo().analyze(dumpId, className).then(r => {
      if (!r.ok) { onStatus(`analyze failed: ${r.error || 'unknown error'}`, true); return; }
      const job = r.data;
      if (!job || job.id == null || job.state === 'done') {
        openViz(kind, dumpId, className, params);
        return;
      }
      onStatus(`analysis ${job.state} — waiting for the job to finish…`);
      const stop = pollJobs(jobs => {
        const j = (jobs || []).find(x => x.id === job.id);
        if (!j) return;
        if (j.state === 'done') { stop(); openViz(kind, dumpId, className, params); }
        else if (j.state === 'failed') {
          stop();
          onStatus(`analysis failed: ${j.error || 'see the jobs panel'}`, true);
        } else onStatus(`analysis ${j.state}…`);
      });
    });
  };
}

export async function openViz(kind, dumpId, className, params = {}) {
  const h = ensureHost();
  const my = ++seq;
  /* a refetch of the same target keeps the scroll position; a new target
     (other kind/class) starts at the top */
  const sameTarget = last && last.kind === kind && last.dumpId === dumpId
    && last.className === className;
  const keepScroll = sameTarget ? h.body.scrollTop : 0;
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
  mod.render(h.body, vm, {
    esc, fmtB, fmtN, catColor, catOf, shortClass,
    onOpenViz: openViz,
    /* the pinned re-slice path: merge params, re-run prepare, re-render here */
    refetch: extra => openViz(kind, dumpId, className, { ...params, ...extra }),
    /* server-only affordance: null in INLINE (snapshot) mode */
    analyze: inlineMode ? null : makeAnalyze(kind, dumpId, className, params),
  });
  if (keepScroll) h.body.scrollTop = keepScroll;
}
