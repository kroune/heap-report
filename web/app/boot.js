// app/boot.js — boot(): the single entry point, called by index.html.
// Wires the dump selector + state badge, tab switching (lazy mounts), the
// jobs panel and the viz registry. Two modes:
//   API    (default) — talks to the backend via data/dumprepo + data/dumpdatarepo
//   INLINE (window.__INLINE__ set — a static snapshot) — same tab/viz UI over
//            makeInlineRepo(payload); server-only UI (.api-only) is hidden.

import { getDump, setDump, onDumpChange, restoreDump } from './state.js';
import { listDumps, startDownload, retryDownload, pollJobs } from '../data/dumprepo.js';
import * as datarepo from '../data/dumpdatarepo.js';
import { makeInlineRepo } from '../data/inlinerepo.js';
import * as classesTab from '../ui/tabs/classes.js';
import * as treemapTab from '../ui/tabs/treemap.js';
import * as compareTab from '../ui/tabs/compare.js';
import { mountJobs } from '../ui/jobs.js';
import { registerViz, initViz } from '../viz/common.js';
import * as anatomy from '../viz/anatomy.js';
import * as hierarchy from '../viz/hierarchy.js';
import * as graph from '../viz/graph.js';

const TABS = [
  ['classes', classesTab],
  ['treemap', treemapTab],
  ['compare', compareTab],
];

export function boot() {
  const payload = typeof window !== 'undefined' && window.__INLINE__ ? window.__INLINE__ : null;
  const repo = payload ? makeInlineRepo(payload) : datarepo;
  initViz(repo);   // viz prepares receive this repo; they never know the mode

  registerViz(anatomy);
  registerViz(hierarchy);
  registerViz(graph);

  restoreDump();            // #dump=<id>, written by setDump()
  wireTabs(repo, !!payload);

  if (payload) {
    document.body.classList.add('inline');   // app.css: body.inline hides .api-only
    bootInline(payload);
  } else {
    mountJobs(document.getElementById('jobs'));
    bootApi();
  }
}

/* ---- tabs: show/hide panes, mount each lazily on first activation ---- */

function wireTabs(repo, inline) {
  const bar = document.getElementById('tabs');
  const mounted = new Set();
  // compare needs two server-side dumps — meaningless in a single-dump snapshot
  if (inline) bar.querySelector('button[data-t="compare"]').hidden = true;
  const activate = (name) => {
    for (const b of bar.querySelectorAll('button')) b.classList.toggle('on', b.dataset.t === name);
    for (const [n, mod] of TABS) {
      const pane = document.getElementById('tab-' + n);
      const on = n === name;
      pane.classList.toggle('on', on);
      if (on && !mounted.has(n)) {
        mounted.add(n);
        mod.mount(pane, repo, {inline});
      }
    }
  };
  bar.addEventListener('click', (e) => {
    const b = e.target.closest('button[data-t]');
    if (b) activate(b.dataset.t);
  });
  activate('classes');
}

/* ---- INLINE mode: a single fixed dump entry from the payload's name ---- */

function bootInline(payload) {
  const name = payload.name || 'snapshot';
  const sel = document.getElementById('dumpsel');
  const opt = document.createElement('option');
  opt.value = name;
  opt.textContent = name;
  sel.replaceChildren(opt);
  sel.disabled = true;
  const badge = document.getElementById('dump-state');
  badge.textContent = 'snapshot';
  badge.className = 'stbadge st-ready';
  setDump(name);
}

/* ---- API mode: selector from listDumps(), state badge, download/retry ---- */

async function bootApi() {
  const sel = document.getElementById('dumpsel');
  sel.addEventListener('change', () => setDump(sel.value));
  onDumpChange((id) => { if (id && sel.value !== id) sel.value = id; });

  let dumps = [];
  const refresh = async () => {
    const res = await listDumps();
    if (!res.ok) { shellError(`cannot load the dump list: ${res.error}`); return; }
    dumps = res.data;
    const cur = getDump();
    sel.replaceChildren(...dumps.map((d) => {
      const opt = document.createElement('option');
      opt.value = d.id;
      opt.textContent = d.state === 'ready' ? d.id : `${d.id} (${d.state})`;
      return opt;
    }));
    // hash-restored/current id wins; otherwise prefer the first ready dump
    if (!cur || !dumps.some((d) => d.id === cur)) {
      const first = dumps.find((d) => d.state === 'ready') || dumps[0];
      if (first) setDump(first.id);
    }
    if (getDump()) sel.value = getDump();
    syncDumpUi(dumps);
  };

  await refresh();
  onDumpChange(() => syncDumpUi(dumps));

  // a finished download changes states/sizes — refresh the list + badge,
  // but only when a job newly reaches a terminal state (they linger in the
  // jobs list, so key on job id to avoid refreshing every tick)
  const seenTerminal = new Set();
  pollJobs((jobs) => {
    let fresh = false;
    for (const j of jobs) {
      if (j.kind === 'download' && (j.state === 'done' || j.state === 'failed')
          && !seenTerminal.has(j.id)) {
        seenTerminal.add(j.id);
        fresh = true;
      }
    }
    if (fresh) refresh();
  });

  const btn = document.getElementById('dl-btn');
  btn.addEventListener('click', async () => {
    const d = dumps.find((x) => x.id === getDump());
    if (!d) return;
    btn.disabled = true;
    const r = d.state === 'failed' ? await retryDownload(d.id) : await startDownload(d.id);
    btn.disabled = false;
    if (!r.ok) shellError(r.error);
    else refresh();
  });
}

function syncDumpUi(dumps) {
  const d = dumps.find((x) => x.id === getDump());
  const badge = document.getElementById('dump-state');
  const btn = document.getElementById('dl-btn');
  if (!d) {
    badge.textContent = '';
    badge.className = 'stbadge';
    btn.hidden = true;
    return;
  }
  badge.textContent = d.state;
  badge.className = 'stbadge st-' + d.state;
  if (d.state === 'remote') { btn.hidden = false; btn.textContent = 'Download'; }
  else if (d.state === 'failed') { btn.hidden = false; btn.textContent = 'Retry download'; }
  else btn.hidden = true;
}

function shellError(msg) {
  const box = document.getElementById('shell-err');
  box.textContent = msg;
  box.hidden = false;
}
