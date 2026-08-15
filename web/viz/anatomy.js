/* viz/anatomy.js — anatomy viz, ONE module for both versions (replaces the old
 * treeHTML/tree2HTML copy-paste). prepare() fetches v1 and v2 (same extraction
 * backs both — v2 is the full-graph rebuild of the same CSVs), the UI switches
 * version via a segment. The row/tree renderer is parameterized on the version;
 * the deliberate payload differences are preserved:
 *   v1 nodes: {name, full, n, s, r, pres?, kids}  — no refs, no sk
 *   v2 nodes: + refs (only when refs > n, i.e. shared within the set), sk flag
 *             on synthetic fields, cause-grouped `untracked`, fullEdges/depth,
 *             and the class-level reference `graph` (teaser links the graph viz)
 *
 * viewModel (contract for the snapshot author + graph agent):
 * {
 *   className, dumpId,
 *   analyzed: false, samplesParam          // 404 {analyzed:false} → "not analyzed" state
 * } OR {
 *   className, dumpId, analyzed: true,
 *   samplesParam,                          // samples= param this prepare used (null = server default)
 *   version,                               // 2 when v2 available, else 1 (initial view)
 *   objCount, shallow, retained,           // the class itself, from trees() leaves (0/0/null if not found)
 *   compTotalShallow,                      // composition().totalShallow, null when not analyzed for composition
 *   v1: {tree, samples, available, roots},
 *   v2: {tree, samples, available, roots, untracked, fullEdges, depth, graph} | null,
 * }
 */

import { buildSeg, fmtB, fmtN, catOf, scaleFactor, orThrow } from './common.js';

export const kind = 'anatomy';

/* ---------- pure transforms (node-testable) ---------- */

/* The old UI got objCount/shallow from the classes-table row the modal was
   opened from; openViz only has a class name, so look the class up in the
   dump trees (histogram leaves carry c/s, dominator leaves carry r). */
export function findClassStats(trees, className) {
  let out = null;
  const walk = n => {
    if (out) return;
    if (n.leaf) {
      if (n.disp === className) out = { c: n.c, s: n.s, r: n.r != null ? n.r : null };
      return;
    }
    for (const c of n.children || []) walk(c);
  };
  walk(trees.hist);
  if (!out) return null;
  const walkDom = n => {
    if (out.r != null) return;
    if (n.leaf) { if (n.disp === className) out.r = n.r; return; }
    for (const c of n.children || []) walkDom(c);
  };
  walkDom(trees.dom);
  return out;
}

/* Row display model for one tree node. o = {v2, G, Mf, K, totRet, objCount}.
   v1 ignores refs/sk entirely (load-bearing difference); the v1 flat
   "(held via untracked/shared refs)" bucket and any "(external)" shared row
   get a dimmed "—" retained cell in global mode (not meaningful per-instance). */
export function buildRowModel(n, o) {
  const prim = n.full === '(field)';
  const shared = n.full === '(external)';
  const untracked = !o.v2 && n.name === '(held via untracked/shared refs)';
  const dimRet = o.G && (shared || untracked);
  const rv = n.r * o.Mf;
  const pct = 100 * rv / o.totRet;
  return {
    cls: prim ? 'prim' : shared ? 'shared' : (o.v2 && n.sk ? 'sk' : ''),
    cat: catOf(n.full || ''),
    expandable: !!(n.kids && n.kids.length),
    pres: n.pres != null ? { in: n.pres, of: o.K } : null,
    refs: o.v2 && n.refs != null ? n.refs : null,
    count: o.G ? fmtN(n.n / o.K) : String(n.n),
    objects: o.G ? `≈ ${fmtN(n.n * o.Mf)}` : fmtN(n.n),
    shallow: o.G ? `≈ ${fmtB(n.s * o.Mf)}` : fmtB(n.s),
    perInstance: fmtB(n.s / o.K),
    dimRet,
    retained: dimRet ? '—' : o.G ? `≈ ${fmtB(rv)}` : fmtB(n.r),
    retPct: !dimRet && o.G && pct >= 0.05 ? `${pct.toFixed(0)}%` : '',
    retPctTitle: pct.toFixed(1),
    skTitle: o.v2 && n.sk
      ? `held via a synthetic field (${n.name.split(':')[0]}) — hidden in the v1 tree` : '',
  };
}

/* v2 untracked group header line. */
export function untrackedSummary(g, o) {
  return {
    name: g.tree.name,
    objects: fmtN(g.n),
    shallow: o.G ? `≈ ${fmtB(g.s * o.Mf)}` : fmtB(g.s),
    retained: o.G ? `≈ ${fmtB(g.r * o.Mf)}` : fmtB(g.r),
  };
}

/* ---------- prepare ---------- */

export async function prepare(repo, dumpId, className, params = {}) {
  const samples = params.samples != null ? params.samples : null;
  const [r1, r2, rc, rt] = await Promise.all([
    repo.anatomy(dumpId, className, { version: 1, samples }),
    repo.anatomy(dumpId, className, { version: 2, samples }),
    repo.composition(dumpId, className),
    repo.trees(dumpId),
  ]);
  if (!r1.ok) {
    if (r1.status === 404 && r1.data && r1.data.analyzed === false)
      return { className, dumpId, analyzed: false, samplesParam: samples };
    throw new Error(r1.error || `anatomy request failed (HTTP ${r1.status})`);
  }
  const v1 = r1.data;
  const v2 = r2.ok ? r2.data : null;   // same extraction backs both; tolerate absence
  const comp = rc.ok ? rc.data : null;
  const st = rt.ok ? findClassStats(rt.data.trees, className) : null;
  return {
    className, dumpId, analyzed: true, samplesParam: samples,
    version: v2 ? 2 : 1,
    objCount: st ? st.c : 0,
    shallow: st ? st.s : 0,
    retained: st ? st.r : null,
    compTotalShallow: comp ? comp.totalShallow : null,
    v1, v2,
  };
}

/* ---------- render ---------- */

const el = (tag, cls, text) => {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
};

function numCell(text, title) {
  const d = el('div', 'num', text);
  if (title) d.title = title;
  return d;
}

function rowEl(n, o) {
  const m = buildRowModel(n, o);
  const row = el('div', 'arow' + (m.cls ? ' ' + m.cls : ''));
  const nm = el('div', 'nm');
  if (m.skTitle) nm.title = m.skTitle;
  nm.appendChild(el('span', 'tgl' + (m.expandable ? '' : ' leaf'), m.expandable ? '▸' : '·'));
  nm.appendChild(el('span', `adot cat-${m.cat}`));
  const label = el('span', '', n.name);
  label.title = n.full || '';
  nm.appendChild(label);
  if (m.pres) {
    const p = el('span', 'pres', `in ${m.pres.in}/${m.pres.of}`);
    p.title = `field non-null in ${m.pres.in} of ${m.pres.of} sampled instances`;
    nm.appendChild(p);
  }
  if (m.refs != null) {
    const r = el('span', 'refs', `⇆${fmtN(m.refs)}`);
    r.title = `${fmtN(m.refs)} inbound references from inside the retained set onto ` +
      `${fmtN(n.n)} object(s) — shared within the set (shown here on the first path only)`;
    nm.appendChild(r);
  }
  nm.appendChild(document.createTextNode(' '));
  const cnt = el('span', 'acnt', `×${m.count}`);
  cnt.title = o.G ? 'average occurrences per instance' : `occurrences across the ${o.K} samples`;
  nm.appendChild(cnt);
  row.appendChild(nm);
  row.appendChild(numCell(m.objects,
    o.G ? `estimated: per-instance average × ${fmtN(o.objCount)} instances` : ''));
  row.appendChild(numCell(m.shallow, o.G ? 'estimated shallow' : ''));
  row.appendChild(numCell(m.perInstance, '')).classList.add('per');
  const rc = el('div', 'num');
  if (m.dimRet) {
    rc.classList.add('dim');
    rc.textContent = m.retained;
    rc.title = 'shared objects — not meaningful when extrapolated per instance';
  } else {
    if (o.G) rc.title = `estimated retained (${m.retPctTitle}% of class total)`;
    rc.textContent = m.retained;
    if (m.retPct) {
      rc.appendChild(document.createTextNode(' '));
      rc.appendChild(el('span', 'pct', m.retPct));
    }
  }
  row.appendChild(rc);
  return row;
}

function nodeEl(n, o, depth) {
  const wrap = el('div');
  const row = rowEl(n, o);
  wrap.appendChild(row);
  if (n.kids && n.kids.length) {
    const kids = el('div', 'anode' + (depth < 1 ? ' open' : ''));
    for (const k of n.kids) kids.appendChild(nodeEl(k, o, depth + 1));
    wrap.appendChild(kids);
    const tgl = row.querySelector('.tgl');
    tgl.addEventListener('click', () => {
      const open = kids.classList.toggle('open');
      tgl.textContent = open ? '▾' : '▸';
    });
  }
  return wrap;
}

export function render(container, vm, ctx) {
  if (!vm.analyzed) {
    const d = el('div', 'viz-empty');
    d.appendChild(el('div', '', 'No anatomy extracted for this class yet.'));
    const hint = el('div', 'hint');
    hint.appendChild(document.createTextNode('Run an analysis with anatomy enabled — '));
    hint.appendChild(el('b', '', 'Analyze button in the classes tab'));
    hint.appendChild(document.createTextNode('.'));
    d.appendChild(document.createElement('br'));
    d.appendChild(hint);
    container.appendChild(d);
    return;
  }

  const view = { version: vm.v2 ? vm.version : 1, scale: 'global' };

  const paint = () => {
    container.textContent = '';
    const a = view.version === 2 ? vm.v2 : vm.v1;
    const K = a.samples;
    const G = view.scale === 'global';
    const Mf = scaleFactor(vm.objCount, K);
    const totRet = Math.max(1, vm.compTotalShallow != null ? vm.compTotalShallow : a.tree.r * Mf);
    const o = { v2: view.version === 2, G, Mf, K, totRet, objCount: vm.objCount };

    // toolbar: version segment (only when v2 exists) + scale + extraction picker
    const tools = el('div', 'viz-tools');
    if (vm.v2) {
      tools.appendChild(buildSeg([
        { value: 1, label: 'v1 reference tree' },
        { value: 2, label: 'v2 full graph' },
      ], view.version, v => { view.version = v; paint(); }));
    }
    tools.appendChild(buildSeg([
      { value: 'sample', label: `${K} sample instances` },
      { value: 'global', label: `× ${fmtN(vm.objCount)} instances (estimated)` },
    ], view.scale, s => { view.scale = s; paint(); }));
    const avail = a.available || [];
    if (avail.length > 1) {
      const ex = el('span', 'viz-extract');
      ex.appendChild(el('span', 'hint', 'extractions:'));
      ex.appendChild(buildSeg(
        avail.map(k => ({ value: k, label: `s${k}` })),
        K,
        k => { if (k !== K) ctx.refetch({ samples: k }); }));
      tools.appendChild(ex);
    }
    container.appendChild(tools);

    // header + tree
    const head = el('div', 'arow head');
    head.appendChild(el('div', '',
      (o.v2 ? 'reference tree v2' : 'reference tree') +
      (G ? ' — extrapolated to all instances' : '') +
      ` (${o.v2 ? 'full graph over the ' : ''}union retained set of ${K} samples)`));
    head.appendChild(el('div', 'num', 'objects'));
    head.appendChild(el('div', 'num', 'shallow'));
    head.appendChild(el('div', 'num', 'per instance'));
    head.appendChild(el('div', 'num', 'retained'));
    container.appendChild(head);
    container.appendChild(nodeEl(a.tree, o, 0));

    if (o.v2) {
      if (a.untracked && a.untracked.length) {
        container.appendChild(el('h3', 'sec',
          `Still unreachable (${a.untracked.length} group${a.untracked.length > 1 ? 's' : ''}) — with their structure, not flat`));
        for (const g of a.untracked) {
          const s = untrackedSummary(g, o);
          const grp = el('div', 'ugrp');
          grp.appendChild(el('b', '', s.name));
          grp.appendChild(document.createTextNode(
            ` — ${s.objects} objects · ${s.shallow} shallow · ${s.retained} retained`));
          container.appendChild(grp);
          const kids = el('div', 'anode open');
          for (const k of g.tree.kids || []) kids.appendChild(nodeEl(k, o, 1));
          container.appendChild(kids);
        }
      } else {
        container.appendChild(el('div', 'ugrp',
          'Every object in the retained set is reachable in the tree above — no untracked remainder.' +
          (a.fullEdges ? '' : ' (Warning: no edgesfull extraction found — re-run the analysis, big arrays may still be hiding children.)')));
      }
      // class-level graph teaser → the graph viz
      if (a.graph && a.graph.nodes && a.graph.nodes.length) {
        const t = el('div', 'viz-teaser');
        t.appendChild(document.createTextNode(
          `class-level reference graph: ${fmtN(a.graph.nodes.length)} classes · ${fmtN(a.graph.links.length)} connections `));
        const b = el('button', '', 'open graph ▸');
        b.addEventListener('click', () => ctx.onOpenViz('graph', vm.dumpId, vm.className));
        t.appendChild(b);
        container.appendChild(t);
      }
    }

    const note = el('div', 'viz-note');
    note.textContent = o.v2
      ? `v2 over the full object graph: complete outbounds ${a.fullEdges ? '(edgesfull extracted — the old 48-slot array cap hides nothing here)' : 'NOT extracted — re-run the analysis, big arrays still hide children!'}, depth cap ${a.depth}, strings & synthetic fields (this$0, dimmed) traversed. ⇆ = inbound refs from inside the set (more refs than objects ⇒ shared within it).`
      : `×N = occurrences across the ${K} samples; "in k/K" = field non-null in k of the ${K} sampled instances (a field missing from some instances is normal — lazily created). "(shared)" = referenced but owned by someone else, so its bytes are not inside this class's retained set.`;
    container.appendChild(note);
  };

  paint();
}
