/* viz/anatomy.js — anatomy viz: the full-graph reference tree of one class's
 * union retained set over K sampled instances (complete outbounds, synthetic
 * fields traversed and dimmed, ⇆ inbound-ref counts, cause-grouped untracked
 * remainder, class-level reference graph teaser).
 *
 * viewModel (contract for the snapshot author + graph agent):
 * {
 *   className, dumpId,
 *   analyzed: false, samplesParam          // 404 {analyzed:false} → "not analyzed" state
 * } OR {
 *   className, dumpId, analyzed: true,
 *   samplesParam,                          // samples= param this prepare used (null = server default)
 *   objCount, shallow, retained,           // the class itself, from trees() leaves (0/0/null if not found)
 *   compTotalShallow,                      // composition().totalShallow, null when not analyzed for composition
 *   anat: {tree, samples, available, roots, untracked, fullEdges, depth, graph},
 * }
 */

import { buildSeg, buildSamplePicker, buildAnalyzePrompt, fmtB, fmtN, catOf, scaleFactor } from './common.js';

export const kind = 'anatomy';

/* ---------- pure transforms (node-testable) ---------- */

/* openViz only has a class name, so objCount/shallow come from the dump
   trees (histogram leaves carry c/s, dominator leaves carry r). */
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

/* Row display model for one tree node. o = {G, Mf, K, objCount, root, sampleR,
   rootExact}. Counts/shallow extrapolate per-instance (Mf = objCount/K).
   Retained is NOT extrapolated: the extraction only contains objects the
   sampled instances dominate — everything shared with unsampled instances is
   invisible to ANY sample (all sharers would have to be sampled), and the
   per-config private ownership that remains is too skewed for a per-instance
   average to converge at realistic K. So: the root row is anchored to the
   EXACT union retained set (rootExact = composition total, shared counted
   once), child rows show raw bytes inside the K-sample set + % of it.
   The "(shared — held by others too)" bucket gets a dimmed "—" retained cell in
   global mode (not meaningful per-instance). "· N more" fold nodes carry their
   biggest overflow kids inside (up to the server's TREE_NODE_BUDGET — a giant
   extraction's fold can be stats-only) — plain expandables, no special-casing. */
export function buildRowModel(n, o) {
  const prim = n.full === '(field)';
  const shared = n.full === '(external)';
  const isRoot = n === o.root;
  const dimRet = o.G && shared;
  const pct = 100 * n.r / Math.max(1, o.sampleR);
  return {
    cls: prim ? 'prim' : shared ? 'shared' : (n.sk ? 'sk' : ''),
    cat: catOf(n.full || ''),
    expandable: !!(n.kids && n.kids.length),
    pres: n.pres != null ? { in: n.pres, of: o.K } : null,
    refs: n.refs != null ? n.refs : null,
    count: o.G ? fmtN(n.n / o.K) : String(n.n),
    objects: o.G ? `≈ ${fmtN(n.n * o.Mf)}` : fmtN(n.n),
    shallow: o.G ? `≈ ${fmtB(n.s * o.Mf)}` : fmtB(n.s),
    perInstance: fmtB(n.s / o.K),
    dimRet,
    retained: dimRet ? '—' : (o.G && isRoot && o.rootExact != null)
      ? `≈ ${fmtB(o.rootExact)}` : fmtB(n.r),
    retPct: !dimRet && !isRoot && pct >= 0.05 ? `${pct.toFixed(0)}%` : '',
    retPctTitle: pct.toFixed(1),
    retTitle: dimRet ? ''
      : (o.G && isRoot && o.rootExact != null)
        ? `EXACT union retained set of all ${fmtN(o.objCount)} instances (shared objects counted once) — the composition total, not an extrapolation`
        : o.G && !isRoot
          ? `bytes inside the ${o.K}-sample set (${pct.toFixed(1)}% of it) — NOT extrapolated: objects shared with unsampled instances never enter any extraction, and per-instance ownership is too skewed to average`
          : '',
    skTitle: n.sk
      ? `held via a synthetic field (${n.name.split(':')[0]})` : '',
  };
}

/* Untracked group header line. */
export function untrackedSummary(g, o) {
  return {
    name: g.tree.name,
    objects: fmtN(g.n),
    shallow: o.G ? `≈ ${fmtB(g.s * o.Mf)}` : fmtB(g.s),
    retained: fmtB(g.r),
  };
}

/* ---------- prepare ---------- */

export async function prepare(repo, dumpId, className, params = {}) {
  const samples = params.samples != null ? params.samples : null;
  const [ra, rc, rt] = await Promise.all([
    repo.anatomy(dumpId, className, { samples }),
    repo.composition(dumpId, className),
    repo.trees(dumpId),
  ]);
  if (!ra.ok) {
    if (ra.status === 404 && ra.data && ra.data.analyzed === false)
      return { className, dumpId, analyzed: false, samplesParam: samples };
    throw new Error(ra.error || `anatomy request failed (HTTP ${ra.status})`);
  }
  const comp = rc.ok ? rc.data : null;
  const st = rt.ok ? findClassStats(rt.data.trees, className) : null;
  return {
    className, dumpId, analyzed: true, samplesParam: samples,
    objCount: st ? st.c : 0,
    shallow: st ? st.s : 0,
    retained: st ? st.r : null,
    compTotalShallow: comp ? comp.totalShallow : null,
    anat: ra.data,
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
    if (m.retTitle) rc.title = m.retTitle;
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
    container.appendChild(buildAnalyzePrompt(ctx, {
      cls: 'viz-empty',
      text: 'No anatomy extracted for this class yet.',
      fallback: 'Run an analysis with anatomy enabled — the Analyze button in the classes tab.',
    }));
    return;
  }

  const view = { scale: 'global' };

  const paint = () => {
    container.textContent = '';
    const a = vm.anat;
    const K = a.samples;
    const G = view.scale === 'global';
    const Mf = scaleFactor(vm.objCount, K);
    // retained: root anchored to the EXACT union retained set (composition
    // total, known for every analyzed class); children stay raw sample bytes —
    // see buildRowModel for why child retained is never extrapolated
    const o = { G, Mf, K, objCount: vm.objCount, root: a.tree, sampleR: a.tree.r,
                rootExact: vm.compTotalShallow };

    // toolbar: scale + extraction picker
    const tools = el('div', 'viz-tools');
    tools.appendChild(buildSeg([
      { value: 'sample', label: `${K} sample instances` },
      { value: 'global', label: `× ${fmtN(vm.objCount)} instances (estimated)` },
    ], view.scale, s => { view.scale = s; paint(); }));
    const avail = a.available || [];
    if (avail.length > 1 || ctx.analyze) {
      const ex = el('span', 'viz-extract');
      ex.appendChild(el('span', 'hint', 'samples:'));
      ex.appendChild(buildSamplePicker({
        available: avail, current: K,
        onSwitch: k => ctx.refetch({ samples: k }),
        analyze: ctx.analyze,
      }));
      tools.appendChild(ex);
    }
    container.appendChild(tools);

    // header + tree
    const head = el('div', 'arow head');
    head.appendChild(el('div', '',
      'reference tree (full graph over the ' +
      `union retained set of ${K} samples)` +
      (G ? ' — counts extrapolated to all instances; root retained = exact class total' : '')));
    head.appendChild(el('div', 'num', 'objects'));
    head.appendChild(el('div', 'num', 'shallow'));
    head.appendChild(el('div', 'num', 'per instance'));
    head.appendChild(el('div', 'num', 'retained'));
    container.appendChild(head);
    container.appendChild(nodeEl(a.tree, o, 0));

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
      const bf = el('button', '', 'open flow ▸');
      bf.addEventListener('click', () => ctx.onOpenViz('flow', vm.dumpId, vm.className));
      t.appendChild(bf);
      container.appendChild(t);
    }

    const note = el('div', 'viz-note');
    note.textContent =
      `Full object graph: complete outbounds ${a.fullEdges ? '(edgesfull extracted — the 48-slot cap of the plain edges extraction hides nothing here)' : 'NOT extracted — re-run the analysis, big arrays still hide children!'}, depth cap ${a.depth}, strings & synthetic fields (this$0, dimmed) traversed. ⇆ = inbound refs from inside the set (more refs than objects ⇒ shared within it). "· N more" folds the smaller kids of one node — click ▸ to expand. ×N = occurrences across the ${K} samples; "in k/K" = field non-null in k of the ${K} sampled instances (a field missing from some instances is normal — lazily created). "(shared)" = referenced but owned by someone else, so its bytes are not inside this class's retained set. In ×N mode objects/shallow extrapolate per-instance; retained is NOT extrapolated — the root row shows the EXACT union retained set of the whole class (the composition total, shared counted once), while child rows show raw bytes inside the sample set + % of it. Child retained can't be extrapolated: objects shared with unsampled instances never enter any extraction, and per-instance ownership is too skewed to average — child shares varying with K is expected, not a bug.`;
    container.appendChild(note);
  };

  paint();
}
