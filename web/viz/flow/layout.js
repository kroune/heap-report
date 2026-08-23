/* Flow viz — pure layout (computeFlowLayout): no DOM, no fetch, no input
   mutation. See index.js for the module map.

   Layout rules:
   - the inspected class is pinned at the top; every other class is layered by
     longest path from it, so edges point DOWN. Class-level cycles (real object
     cycles exist in these heaps: listener registrations, owner/mutationValidator
     back-refs) are broken DFS-style from the root and drawn dashed, routed right.
   - a class referenced by several others appears ONCE — edges converge (this is
     the difference from the tree-shaped anatomy viz). In split mode
     (graph.split) nodes are (class, holder-set) copies instead: one node per
     distinct set of direct holder classes.
   - the min-retained filter hides small classes but never severs chains: edges
     are contracted past hidden top-N-window nodes (A→B→C with B hidden becomes a
     dotted A→C "via" edge carrying the hidden class names for the tooltip). Via
     edges don't count as capture back-refs for the nesting rule below.
   - nested-circle rule: a $-nested class (inner class, $$Lambda) with a REAL
     back-edge to its outer (this$0, arg$1, an owner field) is drawn as a small
     circle inside the outer's circle — the capture edge is implied by
     containment, never drawn. Name-nesting alone is NOT enough: HashMap$Node /
     LinkedHashMap$Entry are chain objects (like Try$Success) and stay normal
     nodes. An inner class bigger than its outer keeps its own node. With split
     copies (duplicate class names) the outer resolves to the copy this node
     actually back-references, never to a same-named stranger.
   - extremely common classes (String, Object[], primitive arrays, maps, …) live
     in a pinned right column and break the top-down rule: edges from anywhere
     route right into the column. In split mode ALL copies of a pinned class sit
     in the column (pins match by class name).
   - node size = INCLUSIVE retained (rincl): everything reachable DOWNWARD from
     the class's in-set instances (back-references to ancestors — owner fields,
     this$0 — don't count; the parent carries its own weight). rshared of that
     is reachable from ≥2 distinct sampled roots — the wedge. Dominator
     retained (r) stays on the node for the detail panel. Rows without the
     extra columns (extraction predates the reach pass) fall back to dominator
     r for sizing/ranking.

   Merged pair edges remember their heaviest ORIGINAL endpoints (os/ot, and
   ros/rot for the reverse of a two-way pair) so tooltips/highlights stay honest
   when nesting remapped several pairs onto one drawn edge. */

export const DEFAULT_TOP = 140;   // top-N classes (by inclusive retained) kept in the layout
export const MIN_TOP = 30;        // floor of the top-N slider
const LAYERH = 78;         // vertical pitch between layers, px
const SPX = 44;            // horizontal gap between adjacent node edges in a layer, px
const RAD_BASE = 5;        // node radius = RAD_BASE + RAD_SPAN*sqrt(rincl/rmax)
const RAD_SPAN = 15;       // …sqrt-scaled so areas stay comparable across dumps
const COL_DX = 130;        // x distance of the pinned column from the main layout
export const COL_SEP_DX = 75;     // column separator line sits this far left of the column
const SWEEPS = 8;          // barycenter sweep iterations (one down+up pass each)
const SHARED_MIN = 3;      // ≥ this many holder classes earns the "shared" ring
export const LABEL_RANK_MAX = 28; // small nodes past this retained-rank label only when zoomed
const NEST_MAX = 8;        // nested circles drawn per host node (rest: "+n" badge)
const LAMBDA_RE = /\$\$Lambda\+0x[0-9a-f]+$/;   // mirrors parsing.py LAMBDA_RE

/* Nearest $-ancestor of cls present in `present` (Set of class names), with the
   lambda suffix stripped first ($$Lambda+0x… belongs to its capturing class).
   null when there is none (e.g. jdk.proxy1.$Proxy70 — the $ sits in the package). */
function outerName(cls, present) {
  const base = cls.replace(LAMBDA_RE, "");
  if (base !== cls && present.has(base)) return base;
  for (let i = base.lastIndexOf("$"); i > 0; i = base.lastIndexOf("$", i - 1)) {
    const o = base.slice(0, i);
    if (present.has(o)) return o;
  }
  return null;
}

/* Pure layout. graph = {nodes, links} where nodes rows are
   [cls, n, shallow, ret] (+ [rincl, rshared] when the reach pass ran, +
   [holders] for split copies). opts = {top, root, pins:[classNames], minR}.
   Returns {nodes, edges, width, height, colX, colCount, layerCount, cycleCount,
   nestCount, rootTop, emax, rmax, inL, outL}. */
export function computeFlowLayout(graph, opts = {}) {
  const g = graph;
  const topN = Math.min(opts.top ?? DEFAULT_TOP, g.nodes.length);
  const root = opts.root ?? null;
  const pinSet = new Set(opts.pins || []);
  const rOf = i => g.nodes[i].length > 4 ? g.nodes[i][4] : g.nodes[i][3];

  // ---- top-N classes by inclusive retained, above the min-retained filter (root
  //      always kept). The min-retained filter hides small classes WITHOUT severing
  //      the reference chains through them: edges are contracted past hidden nodes
  //      of the top-N window (A→B→C with B hidden becomes a dotted A→C "via" edge),
  //      so a big class stays attached to its surviving holders instead of being
  //      dropped as unreachable. ----
  const minR = opts.minR || 0;
  const byR = [...g.nodes.keys()].sort((i, j) => rOf(j) - rOf(i));
  const keepIdx = byR.filter(i => rOf(i) >= minR || g.nodes[i][0] === root).slice(0, topN);
  const keep = new Set(keepIdx);
  const win = new Set(byR.slice(0, topN));   // contraction tunnels stay inside the top-N window
  keepIdx.forEach(i => win.add(i));          // (a small root may sit outside the raw window)
  const remap = new Map(keepIdx.map((oi, i) => [oi, i]));
  const N = keepIdx.map(oi => {
    const row = g.nodes[oi];
    return { oi, cls: row[0], n: row[1], s: row[2], r: row[3],
             rincl: row.length > 4 ? row[4] : row[3],
             rsh: row.length > 5 ? row[5] : 0,
             holders: row.length > 6 ? row[6] : null };
  });
  const adj = new Map();   // window-wide original adjacency for the contraction walks
  for (const l of g.links) {
    if (!win.has(l[0]) || !win.has(l[1])) continue;
    let a = adj.get(l[0]);
    if (!a) adj.set(l[0], a = []);
    a.push({ t: l[1], f: l[2], n: l[3], b: l[4] });
  }
  const inL = N.map(() => []), outL = N.map(() => []);   // field-level refs (click detail): direct only
  const pmap = new Map();
  const pairOf = (s, t) => {
    const k = s + "|" + t;
    let p = pmap.get(k);
    if (!p) { p = { s, t, b: 0, n: 0, via: new Set() }; pmap.set(k, p); }
    return p;
  };
  // direct refs between drawn classes, in payload order (field-level detail feeds too)
  for (const l of g.links) {
    if (!keep.has(l[0]) || !keep.has(l[1])) continue;
    const s = remap.get(l[0]), t = remap.get(l[1]);
    const e = { s, t, f: l[2], n: l[3], b: l[4] };
    inL[t].push(e); outL[s].push(e);
    if (s === t) continue;
    const p = pairOf(s, t);
    p.b += l[4]; p.n += l[3];
  }
  // contracted "via" edges: DFS from each drawn class through hidden window nodes
  // to the next drawn ones (skipped entirely when the filter hides nothing)
  if (win.size > keep.size) {
    for (const u of keepIdx) {
      const s = remap.get(u);
      const seen = new Set([u]);   // one visit per hidden node per source: cycle-safe
      const stack = [];
      for (const a of adj.get(u) || []) if (!keep.has(a.t)) stack.push({ x: a.t, path: [] });
      while (stack.length) {
        const cur = stack.pop();
        if (seen.has(cur.x)) continue;
        seen.add(cur.x);
        const via = [...cur.path, g.nodes[cur.x][0]];
        for (const a of adj.get(cur.x) || []) {
          if (keep.has(a.t)) {   // terminal hop onto a drawn class: one via edge per field link
            if (a.t === u) continue;             // contracted back to the source: not drawable
            const p = pairOf(s, remap.get(a.t));
            p.b += a.b; p.n += a.n;
            via.forEach(c => p.via.add(c));
          } else if (!seen.has(a.t)) {
            stack.push({ x: a.t, path: via });   // keep tunneling
          }
        }
      }
    }
  }
  const D = [...pmap.values()];   // directed class pairs (fields summed, self-loops excluded)
  const rootIdx = root != null ? N.findIndex(nd => nd.cls === root) : -1;

  // ---- nesting: $-nested class with a real back-edge to its outer => circle inside.
  //      The capture edge (and any other inner<->outer edge) is dropped from the
  //      canvas below; the detail panel still lists them. ----
  const present = new Set(N.map(nd => nd.cls));
  const copiesOf = new Map();   // class name -> [node indices] (>1 with split copies)
  N.forEach((nd, u) => {
    const l = copiesOf.get(nd.cls);
    if (l) l.push(u); else copiesOf.set(nd.cls, [u]);
  });
  const hasPair = new Set(D.filter(e => !e.via.size).map(e => e.s + "|" + e.t));   // a contracted "via" edge is no capture back-ref
  const nestIn = N.map(() => -1);
  N.forEach((nd, u) => {
    if (u === rootIdx) return;
    const o = outerName(nd.cls, present);
    if (o === null) return;
    // the connected copy: with split copies several nodes share the outer's
    // class name — nest into the one this node actually back-references
    const oi = (copiesOf.get(o) || []).find(v => v !== u && hasPair.has(u + "|" + v));
    if (oi === undefined) return;   // no capture/owner back-edge: chain objects stay nodes
    if (N[u].rincl > N[oi].rincl) return;   // inner bigger than outer: keep its own node
    for (let v = oi; v !== -1; v = nestIn[v]) if (v === u) return;   // would close a nesting cycle
    nestIn[u] = oi;
  });
  // flatten to the outermost host (multi-level nesting is rare; one ring deep renders)
  const top = u => { let v = u; while (nestIn[v] !== -1) v = nestIn[v]; return v; };
  N.forEach((nd, u) => { if (nestIn[u] !== -1) nestIn[u] = top(u); });

  // ---- layout pairs: nesting remaps endpoints to the outermost host ----
  const L = [], lmap = new Map();
  for (const e of D) {
    const rs = top(e.s), rt = top(e.t);
    if (rs === rt) continue;   // contained (capture edge or host<->inner): implied, not drawn
    const k = rs + "|" + rt;
    let a = lmap.get(k);
    if (!a) { a = { s: rs, t: rt, b: 0, n: 0, os: e.s, ot: e.t, maxb: -1, via: new Set() }; lmap.set(k, a); L.push(a); }
    a.b += e.b; a.n += e.n;
    e.via.forEach(c => a.via.add(c));
    if (e.b > a.maxb) { a.maxb = e.b; a.os = e.s; a.ot = e.t; }
  }
  const lIn = N.map(() => []), lOut = N.map(() => []);
  L.forEach((e, ei) => { lOut[e.s].push(ei); lIn[e.t].push(ei); });

  // ---- reachability: nodes the root cannot reach through kept edges (holders cut
  //      by top-N, or held only from outside the retained set) would float to layer
  //      0 next to the root and lie about being held by it — drop them ----
  const live = N.map(() => true);
  if (rootIdx >= 0) {
    const rt = top(rootIdx), reach = new Set([rt]);
    const q = [rt];
    while (q.length) {
      const u = q.shift();
      for (const ei of lOut[u]) {
        const v = L[ei].t;
        if (!reach.has(v)) { reach.add(v); q.push(v); }
      }
    }
    N.forEach((nd, u) => { if (nestIn[u] === -1 && !reach.has(u)) live[u] = false; });
    N.forEach((nd, u) => { if (nestIn[u] !== -1 && !live[nestIn[u]]) live[u] = false; });
  }

  // ---- cycle break: DFS from the root (heaviest links first, payload order),
  //      edges to on-stack nodes become the dashed up-edges ----
  const color = N.map(() => 0), back = new Set();
  const starts = [...N.keys()].filter(u => live[u]).sort((a, b) => N[b].rincl - N[a].rincl);
  if (rootIdx >= 0) starts.unshift(top(rootIdx));
  for (const s0 of starts) {
    if (color[s0]) continue;
    color[s0] = 1;
    const st = [[s0, 0]];
    while (st.length) {
      const cur = st[st.length - 1], u = cur[0];
      if (cur[1] < lOut[u].length) {
        const ei = lOut[u][cur[1]++], v = L[ei].t;
        if (color[v] === 0) { color[v] = 1; st.push([v, 0]); }
        else if (color[v] === 1) back.add(ei);
      } else { color[u] = 2; st.pop(); }
    }
  }

  // ---- merge directed pairs into render edges: A⇄B collapses to one two-way edge ----
  const dirIdx = new Map(L.map((e, ei) => [e.s + "|" + e.t, ei]));
  const R = [], seenPair = new Set();
  for (let ei = 0; ei < L.length; ei++) {
    const e = L[ei];
    if (!live[e.s] || !live[e.t]) continue;
    const ri = dirIdx.get(e.t + "|" + e.s);
    const pk = Math.min(e.s, e.t) + "|" + Math.max(e.s, e.t);
    if (seenPair.has(pk)) continue;
    if (ri !== undefined) {   // two-way class-level reference
      seenPair.add(pk);
      const o = L[ri], aB = back.has(ei), bB = back.has(ri);
      const fwd = (aB && !bB) ? o : (bB && !aB) ? e : (e.b >= o.b ? e : o);
      const vs = new Set([...e.via, ...o.via]);
      R.push({ s: fwd.s, t: fwd.t, bi: true, b: Math.max(e.b, o.b), n: e.n + o.n,
               os: e.os, ot: e.ot, on: e.n, ob: e.b, ros: o.os, rot: o.ot, rn: o.n, rb: o.b,
               via: vs.size ? [...vs] : null,
               cyc: aB && bB });
    } else {
      R.push({ s: e.s, t: e.t, bi: false, b: e.b, n: e.n, os: e.os, ot: e.ot,
               via: e.via.size ? [...e.via] : null, cyc: back.has(ei) });
    }
  }

  // ---- longest-path layering from the root (ignoring back edges). Live non-root
  //      nodes always keep ≥1 non-back inbound (the DFS tree edge that reached
  //      them), so the root stays alone on layer 0. ----
  const indeg = N.map(() => 0);
  L.forEach((e, ei) => { if (!back.has(ei) && live[e.s] && live[e.t]) indeg[e.t]++; });
  const layer = N.map(() => 0);
  const q = [], seen = new Set();
  indeg.forEach((d, i) => { if (d === 0 && live[i]) q.push(i); });
  if (rootIdx >= 0) {   // the root anchors layer 0
    const rt = top(rootIdx), qi = q.indexOf(rt);
    if (qi > 0) { q.splice(qi, 1); q.unshift(rt); }
  }
  while (q.length) {
    const u = q.shift(); seen.add(u);
    for (const ei of lOut[u]) {
      if (back.has(ei) || !live[L[ei].t]) continue;
      const v = L[ei].t;
      layer[v] = Math.max(layer[v], layer[u] + 1);
      if (--indeg[v] === 0) q.push(v);
    }
  }
  const maxL = Math.max(...layer, 0) + 1;
  for (let i = 0; i < N.length; i++) if (!seen.has(i) && live[i]) layer[i] = maxL;   // pure-cycle leftovers

  // ---- pinned column: pulled out AFTER layout (their edges still layered
  //      holders/held, so nothing floats to the top). Pins match by class NAME:
  //      all split copies of a pinned class sit in the column. ----
  const colU = new Set();
  N.forEach((nd, u) => {
    if (!live[u] || u === rootIdx || nestIn[u] !== -1) return;
    if (pinSet.has(nd.cls)) colU.add(u);
  });

  // ---- order within layers: barycenter sweeps (column nodes included, then removed) ----
  const layersA = [];
  for (let i = 0; i < N.length; i++) {
    if (nestIn[i] !== -1 || !live[i]) continue;   // nested nodes ride with their host
    (layersA[layer[i]] = layersA[layer[i]] || []).push(i);
  }
  for (const ly of layersA) if (ly) ly.sort((a, b) => N[b].rincl - N[a].rincl);
  const positions = () => { const p = N.map(() => 0); layersA.forEach(ly => ly && ly.forEach((u, i) => p[u] = i)); return p; };
  const bc = (u, pos, up) => {
    const rel = up ? lOut[u].filter(ei => !back.has(ei) && live[L[ei].t]).map(ei => pos[L[ei].t])
                   : lIn[u].filter(ei => !back.has(ei) && live[L[ei].s]).map(ei => pos[L[ei].s]);
    return rel.length ? rel.reduce((a, b) => a + b, 0) / rel.length : -1;
  };
  for (let it = 0; it < SWEEPS; it++) {
    let pos = positions();
    for (let d = 1; d < layersA.length; d++)
      if (layersA[d]) layersA[d].sort((a, b) => { const x = bc(a, pos, false), y = bc(b, pos, false);
        return (x < 0 ? Infinity : x) - (y < 0 ? Infinity : y) || N[b].rincl - N[a].rincl; });
    pos = positions();
    for (let d = layersA.length - 2; d >= 0; d--)
      if (layersA[d]) layersA[d].sort((a, b) => { const x = bc(a, pos, true), y = bc(b, pos, true);
        return (x < 0 ? Infinity : x) - (y < 0 ? Infinity : y) || N[b].rincl - N[a].rincl; });
  }
  const Ls = [];
  for (const ly of layersA) { if (!ly) continue; const f = ly.filter(u => !colU.has(u)); if (f.length) Ls.push(f); }

  // ---- coordinates ----
  const rmax = Math.max(...N.map(d => d.rincl), 1);
  const rad = d => RAD_BASE + RAD_SPAN * Math.sqrt(d.rincl / rmax);
  const lw = Ls.map(ly => ly.reduce((a, u) => a + 2 * rad(N[u]) + SPX, -SPX));
  const maxW = Math.max(...lw, 0);
  const totalH = Ls.length * LAYERH;
  const xy = N.map(() => ({ x: 0, y: 0, r: 0 }));
  Ls.forEach((ly, d) => {
    let x = (maxW - lw[d]) / 2;
    for (const u of ly) { x += rad(N[u]); xy[u] = { x, y: d * LAYERH + LAYERH / 2, r: rad(N[u]) }; x += rad(N[u]) + SPX; }
  });
  const colList = [...colU].sort((a, b) => N[b].rincl - N[a].rincl);
  const colX = maxW + COL_DX;
  colList.forEach((u, i) => { xy[u] = { x: colX, y: totalH * (i + 0.5) / Math.max(1, colList.length), r: rad(N[u]) }; });
  const colR = colList.length ? Math.max(...colList.map(u => rad(N[u]))) : 0;
  const totalW = colList.length ? colX + colR + 12 : maxW;

  // ---- nested circles: small arc along the bottom interior of the host ----
  const members = new Map();
  N.forEach((nd, u) => {
    if (nestIn[u] === -1 || !live[nestIn[u]]) return;
    const o = nestIn[u];
    if (!members.has(o)) members.set(o, []);
    members.get(o).push(u);
  });
  const nestShown = N.map(() => []);
  for (const [o, mem] of members) {
    mem.sort((a, b) => N[b].rincl - N[a].rincl);
    const shown = mem.slice(0, NEST_MAX);
    nestShown[o] = shown;
    shown.forEach((m, k) => {
      const rm = Math.max(2, Math.min(rad(N[m]), xy[o].r * 0.34));
      const ang = Math.PI / 2 - 0.95 + 1.9 * (shown.length === 1 ? 0.5 : k / (shown.length - 1));
      const rr = Math.max(0, xy[o].r - rm - 1.2);
      xy[m] = { x: xy[o].x + Math.cos(ang) * rr, y: xy[o].y + Math.sin(ang) * rr, r: rm };
    });
  }

  // ---- edge geometry: cubic routes + arrowhead flags (d = null => not drawn) ----
  const emax = Math.max(...R.map(e => e.b), 1);
  const ew = e => 0.7 + 3.3 * Math.sqrt(e.b / emax);
  const edges = R.map((e, ri) => {
    const colS = colU.has(e.s), colT = colU.has(e.t);
    const out = { s: e.s, t: e.t, bi: e.bi, b: e.b, n: e.n, cyc: !!e.cyc, via: e.via || null,
                  os: e.os, ot: e.ot, w: +ew(e).toFixed(2), d: null, aS: false, aT: false };
    if (e.bi) { out.ros = e.ros; out.rot = e.rot; out.on = e.on; out.ob = e.ob; out.rn = e.rn; out.rb = e.rb; }
    if ((colS && colT) || (colS && !e.bi)) return out;   // column-internal / column outbound: not drawn
    let gs = e.s, gt = e.t;
    if (colS) { gs = e.t; gt = e.s; }                    // draw main → column
    out.aT = (gt === e.t) || e.bi;   // arrowhead at an end iff a directed edge points into it
    out.aS = (gs === e.s) ? e.bi : true;
    const a = xy[gs], b = xy[gt], rs = a.r, rt = b.r;
    if (!colS && !colT && !out.cyc) {
      const y1 = a.y + rs + 1, y2 = b.y - rt - 1, my = (y1 + y2) / 2;
      out.d = `M ${a.x} ${y1} C ${a.x} ${my}, ${b.x} ${my}, ${b.x} ${y2}`;
    } else if (colT) {
      const y1 = a.y + rs + 1, mx = (a.x + b.x) / 2;
      out.d = `M ${a.x} ${y1} C ${mx} ${y1}, ${mx} ${b.y}, ${b.x - rt - 1} ${b.y}`;
    } else {   // cycle back-edge: route around the right of both nodes
      const off = 20 + (ri % 4) * 9, mx = Math.max(a.x, b.x) + off;
      out.d = `M ${a.x} ${a.y - rs} C ${mx} ${a.y - 34}, ${mx} ${b.y + 34}, ${b.x} ${b.y + rt}`;
    }
    return out;
  });

  let nestCount = 0, dropCount = 0;
  const nodes = N.map((nd, u) => {
    if (!live[u]) dropCount++;
    const nested = nestIn[u] !== -1 && live[u];
    if (nested) nestCount++;
    return {
      oi: nd.oi, cls: nd.cls, n: nd.n, s: nd.s, r: nd.r, rincl: nd.rincl, rsh: nd.rsh,
      holders: nd.holders,
      layer: layer[u], col: colU.has(u), nestedIn: nested ? nestIn[u] : -1,
      nest: nestShown[u], nestMore: Math.max(0, (members.get(u) || []).length - nestShown[u].length),
      dropped: !live[u],
      self: inL[u].some(l => l.s === l.t),
      shared: inL[u].filter(l => l.s !== l.t).length >= SHARED_MIN,
      x: xy[u].x, y: xy[u].y, rad: xy[u].r || rad(nd),
    };
  });

  return { nodes, edges, width: totalW, height: totalH,
           colX, colCount: colList.length, layerCount: Ls.length, nestCount, dropCount,
           cycleCount: edges.filter(e => e.cyc && e.d).length,
           rootTop: rootIdx >= 0 ? top(rootIdx) : -1,
           setBytes: rootIdx >= 0 ? N[rootIdx].rincl : 0,
           emax, rmax, inL, outL };
}
