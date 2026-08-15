/* viz/common.js — the viz hub (CONTRACTS.md "Viz contract").
 *
 * Owns:
 *  - the viz registry (registerViz) and openViz(), the ONLY way a viz opens:
 *    popup host (backdrop + panel + close), loading state, error state.
 *    A viz module is {kind, prepare(repo, dumpId, className, params), render}.
 *    prepare does ALL fetching and returns a viewModel; render is dumb.
 *  - ctx.refetch(params): re-runs the current viz's prepare with merged params
 *    and re-renders in place — the pinned path for interactive re-slicing that
 *    needs a fetch (e.g. the anatomy sample-count picker).
 *  - the shared helpers (catColor/shortClass/scaleFactor/buildSeg) — ONE
 *    implementation each; esc/fmtB/fmtN/catOf are delegated from data/http.js.
 *
 * Repo indirection: boot() calls initViz(repo) once with the active repo — the
 * real dumpdatarepo module in API mode, makeInlineRepo(payload) in snapshot
 * mode. openViz passes it to prepare; viz modules never know which mode they
 * run in. No globals: the repo is module-local state set once at boot.
 */

import { trees, anatomy, composition } from '../data/dumpdatarepo.js';
import * as fmt from '../data/http.js';

let activeRepo = null;   // set once by boot() via initViz

export function initViz(repo) {   // called by boot before any viz can open
  activeRepo = repo;
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

let host = null;   // {back, title, sub, body} — built lazily on first openViz

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
  tw.appendChild(title);
  tw.appendChild(sub);
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
  host = { back, title, sub, body };
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

export async function openViz(kind, dumpId, className, params = {}) {
  const h = ensureHost();
  const my = ++seq;
  h.title.textContent = className || kind;
  h.sub.textContent = className ? `${kind} · ${dumpId}` : dumpId;
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
  h.body.textContent = '';
  mod.render(h.body, vm, {
    esc, fmtB, fmtN, catColor, catOf, shortClass,
    onOpenViz: openViz,
    /* the pinned re-slice path: merge params, re-run prepare, re-render here */
    refetch: extra => openViz(kind, dumpId, className, { ...params, ...extra }),
  });
}
