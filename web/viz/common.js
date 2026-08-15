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
 *  - the shared helpers (esc/fmtB/fmtN/catColor/catOf/shortClass/scaleFactor/
 *    buildSeg) — ONE implementation each, mined from the old js/core.js and the
 *    three copy-pasted segmented controls (anatomy v1/v2/graph).
 *
 * Repo indirection (kept tiny on purpose): in API mode prepare receives the
 * real dumpdatarepo module; in INLINE/snapshot mode boot puts a same-interface
 * repo object on window.__INLINE_REPO__ (the single allowed global — a
 * snapshot page has no server). openViz passes whichever is set; viz modules
 * never know which mode they run in.
 */

import { trees, anatomy, composition } from '../data/dumpdatarepo.js';

const API_REPO = { trees, anatomy, composition };

function currentRepo() {
  const w = typeof window !== 'undefined' ? window : null;
  return (w && w.__INLINE_REPO__) || API_REPO;
}

/* ============================== shared helpers ============================== */

export const esc = s => String(s)
  .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;').replace(/'/g, '&#39;');

export const fmtB = v => v >= 1e9 ? (v / 1e9).toFixed(2) + ' GB'
  : v >= 1e6 ? (v / 1e6).toFixed(1) + ' MB'
  : v >= 1e3 ? (v / 1e3).toFixed(1) + ' KB'
  : (v >= 100 ? Math.round(v) : v.toFixed(1)) + ' B';

export const fmtN = v => v >= 1e6 ? (v / 1e6).toFixed(2) + 'M'
  : v >= 1e3 ? (v / 1e3).toFixed(1) + 'k' : '' + v;

const CAT_COLORS = {
  gradle: '#e8743b', agp: '#3ba272', kotlin: '#9b7ede',
  jdk: '#4a90d9', other: '#7d8590',
};

export const catColor = id => CAT_COLORS[id] || CAT_COLORS.other;

export const catOf = n => n.startsWith('org.gradle') ? 'gradle'
  : n.startsWith('com.android') ? 'agp'
  : n.startsWith('org.jetbrains.kotlin') ? 'kotlin'
  : /^java|^jdk|^sun|^com\.sun/.test(n) ? 'jdk' : 'other';

export const shortClass = cls => cls.split('.').pop();

/* Sample→global extrapolation: per-instance average over K samples scaled to
   all objCount instances (old `Mf = Math.max(1, M.objCount)/K`, computed in 3
   places in the old UI). */
export const scaleFactor = (objCount, K) => Math.max(1, objCount) / K;

/* Unwrap a data-layer Result or throw — a throw inside prepare() becomes the
   popup's rendered error state (openViz below). */
export function orThrow(res) {
  if (res && res.ok) return res.data;
  throw new Error((res && res.error) || `request failed (HTTP ${res && res.status})`);
}

/* The ONE segmented control (old code had 3 copies: anatomy v1, anatomy v2,
   graph). options: [{value, label, title?}]; the clicked button gets .on,
   then onPick(value) fires. Pure DOM, no fetch — callers decide whether
   onPick re-renders locally or goes through ctx.refetch. */
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
