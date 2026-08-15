/* viz/hierarchy.js — package-hierarchy drill view (icicle).
 * Data comes from trees(id): the dominator tree
 * (category → package → class leaves {name, disp, cat, c, s, r, leaf}) —
 * width = share of parent, click = descend, breadcrumb = back up,
 * double-click a class leaf = open its anatomy viz. Metric segment switches
 * retained / shallow / objects; all slicing is local (no refetch).
 *
 * viewModel:
 * {
 *   dumpId,
 *   className,          // class the viz was opened on, null for a dump-level open
 *   metric,             // 'r' | 's' | 'n' (retained / shallow / objects)
 *   path,               // pre-zoom: node names from root to the class's package
 *   tree,               // dominator tree with values aggregated bottom-up:
 *                       //   {name, cat?, disp?, leaf?, c, s, r, children:[...]}
 *   stats,              // trees().stats, for the header line
 * }
 */

import { buildSeg, fmtB, fmtN, catColor, orThrow } from './common.js';

export const kind = 'hierarchy';

const NS = 'http://www.w3.org/2000/svg';

/* ---------- pure transforms (node-testable) ---------- */

/* Internal tree nodes carry no c/s/r — aggregate bottom-up (leaves keep their
   own values; the "· other" aggregate leaves included). */
export function withValues(n) {
  const kids = (n.children || []).map(withValues);
  if (n.leaf || !kids.length) {
    return { name: n.name, disp: n.disp || '', cat: n.cat || 'other', leaf: n.leaf || 0,
             c: n.c || 0, s: n.s || 0, r: n.r || 0, children: kids };
  }
  return { name: n.name, disp: '', cat: n.cat || 'other', leaf: 0,
           c: kids.reduce((a, k) => a + k.c, 0),
           s: kids.reduce((a, k) => a + k.s, 0),
           r: kids.reduce((a, k) => a + k.r, 0),
           children: kids };
}

/* Names to descend from the root so the package containing className is the
   zoom root (empty array when the class is not in the tree). */
export function findPath(root, className) {
  const dfs = (n, acc) => {
    if (n.leaf) return n.disp === className ? acc : null;
    for (const k of n.children || []) {
      const p = dfs(k, [...acc, k.name]);
      if (p) return p;
    }
    return null;
  };
  const p = dfs(root, []);
  return p ? p.slice(0, -1) : [];   // drop the leaf itself: zoom to its package
}

const metricVal = (n, m) => m === 'r' ? n.r : m === 's' ? n.s : n.c;

/* Icicle layout: kids sized by share of parent, widest first,
   rows of ROWH, cells narrower than 0.7px and everything past CAP dropped. */
export function layoutCells(zroot, W, metric) {
  const ROWH = 22, CAP = 2400;
  const cells = [];
  const lay = (n, x, w, depth) => {
    if (cells.length > CAP) return;
    cells.push({ n, x, w, depth });
    const kids = (n.children || []).filter(k => metricVal(k, metric) > 0)
      .sort((p, q) => metricVal(q, metric) - metricVal(p, metric));
    const tot = kids.reduce((t, k) => t + metricVal(k, metric), 0);
    if (tot <= 0) return;
    let cx = x;
    for (const k of kids) {
      const kw = w * metricVal(k, metric) / tot;
      if (kw >= 0.7 && cells.length <= CAP) lay(k, cx, kw, depth + 1);
      cx += kw;
    }
  };
  lay(zroot, 0, W, 0);
  const maxD = Math.max(...cells.map(c => c.depth), 0);
  return { cells, H: (maxD + 1) * ROWH + 8, ROWH };
}

/* ---------- prepare ---------- */

export async function prepare(repo, dumpId, className, params = {}) {
  const data = orThrow(await repo.trees(dumpId));
  const tree = withValues(data.trees.dom);
  return {
    dumpId,
    className: className || null,
    metric: params.metric || 'r',
    path: className ? findPath(tree, className) : [],
    tree,
    stats: data.stats,
  };
}

/* ---------- render ---------- */

const el = (tag, cls, text) => {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
};

export function render(container, vm, ctx) {
  const view = { metric: vm.metric, path: vm.path.slice() };
  const tip = el('div', 'viz-tip');

  const paint = () => {
    container.textContent = '';
    const m = view.metric;
    const fmtV = v => m === 'n' ? fmtN(v) : fmtB(v);

    const tools = el('div', 'viz-tools');
    tools.appendChild(buildSeg([
      { value: 'r', label: 'retained' },
      { value: 's', label: 'shallow' },
      { value: 'n', label: 'objects' },
    ], m, v => { view.metric = v; paint(); }));
    tools.appendChild(el('span', 'hint',
      'the dominator tree as a top-down hierarchy — width = share of parent · click = descend · breadcrumb = back up · double-click a class = open its anatomy'));
    container.appendChild(tools);

    // zoom root by descending the path by name (reset when a name vanished)
    let zroot = vm.tree;
    for (const nm of view.path) {
      const k = (zroot.children || []).find(c => c.name === nm);
      if (!k) { view.path = []; zroot = vm.tree; break; }
      zroot = k;
    }

    const crumbs = el('div', 'hiercrumbs');
    [vm.tree.name, ...view.path].forEach((nm, i) => {
      if (i) crumbs.appendChild(document.createTextNode(' › '));
      const a = el('a', '', nm.length > 46 ? nm.slice(0, 46) + '…' : nm);
      a.addEventListener('click', () => { view.path = view.path.slice(0, i); paint(); });
      crumbs.appendChild(a);
    });
    container.appendChild(crumbs);

    const svg = document.createElementNS(NS, 'svg');
    svg.setAttribute('class', 'hier');
    container.appendChild(svg);
    container.appendChild(tip);

    const W = container.clientWidth || 1000;
    const { cells, H, ROWH } = layoutCells(zroot, W, m);
    const zv = Math.max(1, metricVal(zroot, m));
    svg.setAttribute('viewBox', `0 0 ${W} ${H}`);
    svg.style.height = Math.min(900, H) + 'px';

    for (const c of cells) {
      const g = document.createElementNS(NS, 'g');
      const r = document.createElementNS(NS, 'rect');
      r.setAttribute('x', c.x);
      r.setAttribute('y', c.depth * ROWH);
      r.setAttribute('width', Math.max(0, c.w - 0.6));
      r.setAttribute('height', ROWH - 1.5);
      r.setAttribute('fill', catColor(c.n.cat));
      r.setAttribute('rx', 1);
      g.appendChild(r);
      if (c.w > 46) {
        const t = document.createElementNS(NS, 'text');
        t.setAttribute('x', c.x + 4);
        t.setAttribute('y', c.depth * ROWH + 15);
        t.setAttribute('class', 'hier-label');
        const maxCh = Math.floor((c.w - 8) / 6.4);
        let label = c.n.name;
        if (label.length > maxCh) label = label.slice(0, Math.max(1, maxCh - 1)) + '…';
        t.textContent = label;
        g.appendChild(t);
        if (c.w > 150) {
          const v = document.createElementNS(NS, 'text');
          v.setAttribute('x', c.x + c.w - 6);
          v.setAttribute('y', c.depth * ROWH + 15);
          v.setAttribute('class', 'hier-val');
          v.textContent = `${fmtV(metricVal(c.n, m))} · ${(100 * metricVal(c.n, m) / zv).toFixed(1)}%`;
          g.appendChild(v);
        }
      }
      g.addEventListener('mousemove', e => showTip(e, c.n, zv));
      g.addEventListener('mouseleave', () => { tip.style.display = 'none'; });
      if ((c.n.children || []).length)
        g.addEventListener('click', () => { view.path.push(c.n.name); paint(); });
      if (c.n.leaf && c.n.disp && !c.n.name.startsWith('·'))
        g.addEventListener('dblclick', () => ctx.onOpenViz('anatomy', vm.dumpId, c.n.disp));
      svg.appendChild(g);
    }
  };

  const showTip = (e, n, zv) => {
    const m = view.metric;
    tip.textContent = '';
    tip.appendChild(el('div', 't-name', n.disp || n.name));
    const tbl = el('table');
    const tr = (k, v, extra) => {
      const row = el('tr');
      row.appendChild(el('td', '', k));
      row.appendChild(el('td', 'v', v));
      row.appendChild(el('td', 'v', extra || ''));
      tbl.appendChild(row);
    };
    tr('retained', fmtB(n.r), `${(100 * n.r / Math.max(1, vm.tree.r)).toFixed(1)}% of dump`);
    tr('shallow', fmtB(n.s));
    tr('objects', fmtN(n.c));
    tr('in this view', `${(100 * metricVal(n, m) / zv).toFixed(1)}%`);
    tip.appendChild(tbl);
    tip.appendChild(el('div', 't-hint',
      n.leaf ? 'double-click — open class anatomy' : 'click — descend · double-click a class — open anatomy'));
    tip.style.display = 'block';
    const tw = tip.offsetWidth, th = tip.offsetHeight;
    tip.style.left = Math.min(e.clientX + 14, document.documentElement.clientWidth - tw - 10) + 'px';
    tip.style.top = Math.min(e.clientY + 14, document.documentElement.clientHeight - th - 10) + 'px';
  };

  paint();
}
