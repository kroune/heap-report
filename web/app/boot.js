// app/boot.js — boot(): the single entry point, called by index.html.
// Wires the dump picker (ui/dumppicker.js) + state badge, tab switching
// (lazy mounts), the jobs panel and the viz registry. Two modes:
//   API    (default) — talks to the backend via data/dumprepo + data/dumpdatarepo
//   INLINE (window.__INLINE__ set — a static snapshot) — same tab/viz UI over
//            makeInlineRepo(payload); server-only UI (.api-only) is hidden.

import { getDump, setDump, onDumpChange, restoreDump } from './state.js';
import { listDumps, pollJobs } from '../data/dumprepo.js';
import * as datarepo from '../data/dumpdatarepo.js';
import { makeInlineRepo } from '../data/inlinerepo.js';
import * as classesTab from '../ui/tabs/classes.js';
import * as treemapTab from '../ui/tabs/treemap.js';
import * as compareTab from '../ui/tabs/compare.js';
import { mountJobs } from '../ui/jobs.js';
import { mountDumpPicker } from '../ui/dumppicker.js';
import { registerViz, initViz } from '../viz/common.js';
import * as anatomy from '../viz/anatomy.js';
import * as hierarchy from '../viz/hierarchy.js';
import * as graph from '../viz/graph.js';
import * as flow from '../viz/flow.js';

const TABS = [
  ['classes', classesTab],
  ['treemap', treemapTab],
  ['compare', compareTab],
];

export function boot() {
  const payload = typeof window !== 'undefined' && window.__INLINE__ ? window.__INLINE__ : null;
  const repo = payload ? makeInlineRepo(payload) : datarepo;
  initViz(repo, {inline: !!payload});   // viz prepares receive this repo; they never know the mode

  registerViz(anatomy);
  registerViz(hierarchy);
  registerViz(graph);
  registerViz(flow);

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
  const btn = document.getElementById('dumpsel-btn');
  btn.textContent = name;
  btn.disabled = true;
  const badge = document.getElementById('dump-state');
  badge.textContent = 'snapshot';
  badge.className = 'stbadge st-ready';
  setDump(name);
}

/* ---- API mode: header button opens the dump picker (ui/dumppicker.js),
   which owns selection, tag filtering/editing and the per-dump lifecycle
   actions; boot owns the list itself and the state badge ---- */

async function bootApi() {
  const selBtn = document.getElementById('dumpsel-btn');
  let dumps = [];
  const liveDl = new Set();   // dump ids with an active (queued/running) download job

  const refresh = async () => {
    const res = await listDumps();
    if (!res.ok) { shellError(`cannot load the dump list: ${res.error}`); return; }
    dumps = res.data;
    const cur = getDump();
    // hash-restored/current id wins; otherwise prefer the first ready dump,
    // then any local dump (a busy one's overview may already work)
    if (!cur || !dumps.some((d) => d.id === cur)) {
      const first = dumps.find((d) => d.state === 'ready')
        || dumps.find((d) => d.state !== 'remote' && d.state !== 'failed')
        || dumps[0];
      if (first) setDump(first.id);
    }
    syncHeader(dumps);
    picker.update(dumps, liveDl);
  };

  const picker = mountDumpPicker({
    onSelect: (id) => setDump(id),
    onRefresh: () => refresh(),
  });
  selBtn.addEventListener('click', () => picker.open());

  await refresh();
  onDumpChange(() => { syncHeader(dumps); picker.update(dumps, liveDl); });

  // a finished download changes states/sizes — refresh the list + badge,
  // but only when a job newly reaches a terminal state (they linger in the
  // jobs list, so key on job id to avoid refreshing every tick)
  const seenTerminal = new Set();
  pollJobs((jobs) => {
    liveDl.clear();
    for (const j of jobs) {
      if (j.kind === 'download' && (j.state === 'queued' || j.state === 'running')) {
        liveDl.add(j.dump);
      }
    }
    let fresh = false;
    for (const j of jobs) {
      if (j.kind === 'download' && (j.state === 'done' || j.state === 'failed')
          && !seenTerminal.has(j.id)) {
        seenTerminal.add(j.id);
        fresh = true;
      }
    }
    if (fresh) refresh();
    else picker.update(dumps, liveDl);   // a download job may appear/vanish without a terminal transition
  });
}

function syncHeader(dumps) {
  const btn = document.getElementById('dumpsel-btn');
  const badge = document.getElementById('dump-state');
  const id = getDump();
  const d = id ? dumps.find((x) => x.id === id) : null;
  btn.textContent = id ? id + ' ▾' : 'Select dump ▾';
  badge.textContent = d ? d.state : '';
  badge.className = 'stbadge' + (d ? ' st-' + d.state : '');
}

function shellError(msg) {
  const box = document.getElementById('shell-err');
  box.textContent = msg;
  box.hidden = false;
}
