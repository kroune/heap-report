/* Flow viz: the class-level reference DAG drawn as a strict top-down "anatomy"
   of the inspected class. Fed by the same anatomy payload as the graph viz
   (GET /api/dumps/{id}/anatomy?class=…&samples=…;
   {samples, available, graph:{nodes:[[cls,n,shallow,retained]], links:[[s,t,field,n,bytes]]}}).

   Layout rules (computeFlowLayout, pure — no DOM, no fetch, no input mutation):
   - the inspected class is pinned at the top; every other class is layered by
     longest path from it, so edges point DOWN. Class-level cycles (real object
     cycles exist in these heaps: listener registrations, owner/mutationValidator
     back-refs) are broken DFS-style from the root and drawn dashed, routed right.
   - a class referenced by several others appears ONCE — edges converge (this is
     the difference from the tree-shaped anatomy viz).
   - nested-circle rule: a $-nested class (inner class, $$Lambda) with a REAL
     back-edge to its outer (this$0, arg$1, an owner field) is drawn as a small
     circle inside the outer's circle — the capture edge is implied by
     containment, never drawn. Name-nesting alone is NOT enough: HashMap$Node /
     LinkedHashMap$Entry are chain objects (like Try$Success) and stay normal
     nodes. An inner class bigger than its outer keeps its own node.
   - extremely common classes (String, Object[], primitive arrays, maps, …) live
     in a pinned right column and break the top-down rule: edges from anywhere
     route right into the column. The pin set is user-editable (detail panel
     button + chips bar) and persists in localStorage (one global set).
   Pinned nodes still layer their holders/held before being pulled out (nothing
   floats to the top); their own outbound refs are listed, not drawn.

   Merged pair edges remember their heaviest ORIGINAL endpoints (os/ot, and
   ros/rot for the reverse of a two-way pair) so tooltips/highlights stay honest
   when nesting remapped several pairs onto one drawn edge. */

import { fmtB, fmtN, catOf, shortClass, scaleFactor } from "./common.js";
import { findClassStats } from "./anatomy.js";

export const kind = "flow";

const SVGNS = "http://www.w3.org/2000/svg"; // namespace for createElementNS
const DEFAULT_TOP = 140;   // top-N classes (by retained bytes) kept in the layout
const MIN_TOP = 30;        // floor of the top-N slider
const LAYERH = 78;         // vertical pitch between layers, px
const SPX = 44;            // horizontal gap between adjacent node edges in a layer, px
const RAD_BASE = 5;        // node radius = RAD_BASE + RAD_SPAN*sqrt(retained/rmax)
const RAD_SPAN = 15;       // …sqrt-scaled so areas stay comparable across dumps
const COL_DX = 130;        // x distance of the pinned column from the main layout
const COL_SEP_DX = 75;     // column separator line sits this far left of the column
const SWEEPS = 8;          // barycenter sweep iterations (one down+up pass each)
const DETAIL_ROWS = 14;    // max inbound/outbound rows in the click detail panel
const SHARED_MIN = 3;      // ≥ this many holder classes earns the "shared" ring
const LABEL_RANK_MAX = 28; // small nodes past this retained-rank label only when zoomed
const NEST_MAX = 8;        // nested circles drawn per host node (rest: "+n" badge)
const LAMBDA_RE = /\$\$Lambda\+0x[0-9a-f]+$/;   // mirrors parsing.py LAMBDA_RE
const PINS_KEY = "heap-report.flow.pins.v1";    // localStorage key for the pin set

const DEFAULT_PINS = [
  "java.lang.String", "java.lang.Object[]",
  "byte[]", "char[]", "short[]", "int[]", "long[]", "float[]", "double[]", "boolean[]",
  "java.util.HashMap", "java.util.LinkedHashMap", "java.util.concurrent.ConcurrentHashMap",
];

/* The pin set: one global list, user-edited. Guards: sandboxed frames can throw
   on localStorage access, and a corrupt value falls back to defaults. */
function loadPins() {
  try {
    const v = JSON.parse(localStorage.getItem(PINS_KEY) || "null");
    if (Array.isArray(v) && v.every(x => typeof x === "string")) return v;
  } catch (e) { /* no storage available — defaults */ }
  return [...DEFAULT_PINS];
}
function savePins(p) {
  try { localStorage.setItem(PINS_KEY, JSON.stringify(p)); } catch (e) { /* no storage */ }
}

/* "5m" / "500k" / "1.5g" / "8000000" -> bytes; null when unparseable */
const parseBytes = s => {
  const m = /^\s*(\d+(?:\.\d+)?)\s*([kmg])?b?\s*$/i.exec(s);
  if (!m) return null;
  const mult = { "": 1, k: 1 << 10, m: 1 << 20, g: 1 << 30 }[(m[2] || "").toLowerCase()];
  return Math.round(parseFloat(m[1]) * mult);
};

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

/* Pure layout. graph = {nodes, links}, opts = {top, root, pins:[classNames]}.
   Returns {nodes, edges, width, height, colX, colCount, layerCount, cycleCount,
   nestCount, rootTop, emax, rmax, inL, outL}. */
export function computeFlowLayout(graph, opts = {}) {
  const g = graph;
  const topN = Math.min(opts.top ?? DEFAULT_TOP, g.nodes.length);
  const root = opts.root ?? null;
  const pinSet = new Set(opts.pins || []);

  // ---- top-N classes by retained, above the min-retained filter (root always kept) ----
  const minR = opts.minR || 0;
  const keepIdx = [...g.nodes.keys()]
    .filter(i => g.nodes[i][3] >= minR || g.nodes[i][0] === root)
    .sort((i, j) => g.nodes[j][3] - g.nodes[i][3]).slice(0, topN);
  const keep = new Set(keepIdx);
  const remap = new Map(keepIdx.map((oi, i) => [oi, i]));
  const N = keepIdx.map(oi => ({ oi, cls: g.nodes[oi][0], n: g.nodes[oi][1], s: g.nodes[oi][2], r: g.nodes[oi][3] }));
  const inL = N.map(() => []), outL = N.map(() => []);   // field-level refs (click detail)
  const pmap = new Map();
  for (const l of g.links) {
    if (!keep.has(l[0]) || !keep.has(l[1])) continue;
    const s = remap.get(l[0]), t = remap.get(l[1]);
    const e = { s, t, f: l[2], n: l[3], b: l[4] };
    inL[t].push(e); outL[s].push(e);
    if (s === t) continue;
    const k = s + "|" + t;
    let p = pmap.get(k);
    if (!p) { p = { s, t, b: 0, n: 0 }; pmap.set(k, p); }
    p.b += l[4]; p.n += l[3];
  }
  const D = [...pmap.values()];   // directed class pairs (fields summed, self-loops excluded)
  const rootIdx = root != null ? N.findIndex(nd => nd.cls === root) : -1;

  // ---- nesting: $-nested class with a real back-edge to its outer => circle inside.
  //      The capture edge (and any other inner<->outer edge) is dropped from the
  //      canvas below; the detail panel still lists them. ----
  const present = new Set(N.map(nd => nd.cls));
  const idxOf = new Map(N.map((nd, u) => [nd.cls, u]));
  const hasPair = new Set(D.map(e => e.s + "|" + e.t));
  const nestIn = N.map(() => -1);
  N.forEach((nd, u) => {
    if (u === rootIdx) return;
    const o = outerName(nd.cls, present);
    if (o === null) return;
    const oi = idxOf.get(o);
    if (oi === undefined || oi === u) return;
    if (!hasPair.has(u + "|" + oi)) return;   // no capture/owner back-edge: chain objects stay nodes
    if (N[u].r > N[oi].r) return;             // inner bigger than outer: keep its own node
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
    if (!a) { a = { s: rs, t: rt, b: 0, n: 0, os: e.s, ot: e.t, maxb: -1 }; lmap.set(k, a); L.push(a); }
    a.b += e.b; a.n += e.n;
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
  const starts = [...N.keys()].filter(u => live[u]).sort((a, b) => N[b].r - N[a].r);
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
      R.push({ s: fwd.s, t: fwd.t, bi: true, b: Math.max(e.b, o.b), n: e.n + o.n,
               os: e.os, ot: e.ot, on: e.n, ob: e.b, ros: o.os, rot: o.ot, rn: o.n, rb: o.b,
               cyc: aB && bB });
    } else {
      R.push({ s: e.s, t: e.t, bi: false, b: e.b, n: e.n, os: e.os, ot: e.ot, cyc: back.has(ei) });
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
  //      holders/held, so nothing floats to the top) ----
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
  for (const ly of layersA) if (ly) ly.sort((a, b) => N[b].r - N[a].r);
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
        return (x < 0 ? Infinity : x) - (y < 0 ? Infinity : y) || N[b].r - N[a].r; });
    pos = positions();
    for (let d = layersA.length - 2; d >= 0; d--)
      if (layersA[d]) layersA[d].sort((a, b) => { const x = bc(a, pos, true), y = bc(b, pos, true);
        return (x < 0 ? Infinity : x) - (y < 0 ? Infinity : y) || N[b].r - N[a].r; });
  }
  const Ls = [];
  for (const ly of layersA) { if (!ly) continue; const f = ly.filter(u => !colU.has(u)); if (f.length) Ls.push(f); }

  // ---- coordinates ----
  const rmax = Math.max(...N.map(d => d.r), 1);
  const rad = d => RAD_BASE + RAD_SPAN * Math.sqrt(d.r / rmax);
  const lw = Ls.map(ly => ly.reduce((a, u) => a + 2 * rad(N[u]) + SPX, -SPX));
  const maxW = Math.max(...lw, 0);
  const totalH = Ls.length * LAYERH;
  const xy = N.map(() => ({ x: 0, y: 0, r: 0 }));
  Ls.forEach((ly, d) => {
    let x = (maxW - lw[d]) / 2;
    for (const u of ly) { x += rad(N[u]); xy[u] = { x, y: d * LAYERH + LAYERH / 2, r: rad(N[u]) }; x += rad(N[u]) + SPX; }
  });
  const colList = [...colU].sort((a, b) => N[b].r - N[a].r);
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
    mem.sort((a, b) => N[b].r - N[a].r);
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
    const out = { s: e.s, t: e.t, bi: e.bi, b: e.b, n: e.n, cyc: !!e.cyc,
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
      oi: nd.oi, cls: nd.cls, n: nd.n, s: nd.s, r: nd.r,
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
           setBytes: rootIdx >= 0 ? N[rootIdx].r : 0,
           emax, rmax, inL, outL };
}

/* Data step: fetch the anatomy payload and build the viewModel. params =
   {top, samples, scale, objCount, cyc, pins} — interactive controls round-trip
   through ctx.refetch(params), which re-runs prepare. */
export async function prepare(repo, dumpId, className, params = {}) {
  const res = await repo.anatomy(dumpId, className, { samples: params.samples ?? null });
  if (!res.ok) {
    if (res.status === 404) return { kind, dumpId, className, notAnalyzed: true };
    return { kind, dumpId, className, error: res.error || "anatomy query failed" };
  }
  const a = res.data;
  if (!a || !a.graph || !a.graph.nodes) return { kind, dumpId, className, notAnalyzed: true };

  const maxNodes = a.graph.nodes.length;
  const top = Math.max(1, Math.min(params.top ?? DEFAULT_TOP, maxNodes));
  const minR = Math.max(0, params.minR || 0);
  const pins = (params.pins || loadPins()).slice();

  // extrapolation to the whole dump needs the class's instance count; if the
  // opener didn't pass it in params, look it up in the dump trees (same trick
  // as the graph viz) — without it global mode stays off
  let objCount = Number.isFinite(params.objCount) ? params.objCount : null;
  if (objCount === null) {
    const rt = await repo.trees(dumpId);
    const st = rt.ok ? findClassStats(rt.data.trees, className) : null;
    if (st && Number.isFinite(st.c)) objCount = st.c;
  }
  const scale = params.scale === "sample" || !objCount ? "sample" : "global";
  const factor = objCount ? scaleFactor(objCount, a.samples) : 1;

  // the min-retained filter is entered in DISPLAYED numbers: global mode
  // extrapolates r × factor, so the layout threshold is minR / factor
  const effMinR = scale === "global" ? minR / factor : minR;
  const layout = computeFlowLayout(a.graph, { top, root: className, pins, minR: effMinR });
  const nodes = layout.nodes;
  nodes.forEach(nd => { nd.cat = catOf(nd.cls); });

  const labels = nodes.map(nd => {
    const s = shortClass(nd.cls);
    return s.length > 30 ? s.slice(0, 29) + "…" : s;
  });
  const details = nodes.map((nd, u) => ({
    cls: nd.cls, short: shortClass(nd.cls), n: nd.n, s: nd.s, r: nd.r,
    layer: nd.layer, col: nd.col, nestedIn: nd.nestedIn, nestCount: nd.nest.length + nd.nestMore,
    self: nd.self, shared: nd.shared,
    ins: layout.inL[u].filter(l => l.s !== l.t).sort((x, y) => y.b - x.b).slice(0, DETAIL_ROWS)
      .map(l => ({ cls: nodes[l.s].cls, short: shortClass(nodes[l.s].cls), f: l.f, n: l.n, b: l.b })),
    outs: layout.outL[u].filter(l => l.s !== l.t).sort((x, y) => y.b - x.b).slice(0, DETAIL_ROWS)
      .map(l => ({ cls: nodes[l.t].cls, short: shortClass(nodes[l.t].cls), f: l.f, n: l.n, b: l.b })),
  }));
  layout.edges.forEach(e => {
    if (e.d === null) return;
    const nm = u => shortClass(nodes[u].cls);
    if (e.bi) {
      e.tip = `${nm(e.os)} → ${nm(e.ot)}: ×${fmtN(e.on)}, ${fmtB(e.ob)}\n` +
              `${nm(e.ros)} → ${nm(e.rot)}: ×${fmtN(e.rn)}, ${fmtB(e.rb)}`;
    } else {
      e.tip = `${nm(e.os)} → ${nm(e.ot)}: ×${fmtN(e.n)}, ${fmtB(e.b)}` +
              (e.cyc ? "\nclass-level cycle edge (points up)" : "");
    }
  });

  return { kind, dumpId, className, samples: a.samples, available: a.available || [],
           objCount, scale, factor, cyc: params.cyc !== false,
           top, maxNodes, pins, minR, setBytes: layout.setBytes, layout, labels, details };
}

/* Dumb renderer: SVG building + pan/zoom + click detail + pin editor, from the
   viewModel only. Data-changing interactions (top-N, sample count, pins) go
   through ctx.refetch(params). */
export function render(container, vm, ctx) {
  container.textContent = "";
  if (vm.notAnalyzed || vm.error) {
    const d = document.createElement("div");
    d.className = "gnote";
    if (vm.error) {
      d.classList.add("gerr");
      d.textContent = vm.error;
    } else {
      d.textContent = "No anatomy extracted for this class yet — the flow graph is built from the anatomy " +
        "extraction.";
      if (ctx.analyze) {
        const b = document.createElement("button");
        b.className = "viz-anbtn";
        b.textContent = "Analyze this class";
        const status = document.createElement("div");
        status.className = "viz-anstatus";
        b.addEventListener("click", () => {
          b.disabled = true;
          ctx.analyze((text, isErr) => {
            status.textContent = text;
            status.classList.toggle("err", !!isErr);
            if (isErr) b.disabled = false;
          });
        });
        d.appendChild(b);
        d.appendChild(status);
      } else {
        const hint = document.createElement("div");
        hint.className = "hint";
        hint.textContent = "Run the analysis with anatomy enabled, then reopen the flow graph.";
        d.appendChild(hint);
      }
    }
    container.appendChild(d);
    return;
  }

  const layout = vm.layout, N = layout.nodes, E = layout.edges;
  let scale = vm.scale, cycOn = vm.cyc, shown = -1, nb = new Set();   // nb = the selected node's neighbors (their labels stay bright)
  const G = () => scale === "global";
  const fxB = v => G() ? "≈ " + ctx.fmtB(v * vm.factor) : ctx.fmtB(v);
  const fxN = v => G() ? "≈ " + ctx.fmtN(v * vm.factor) : ctx.fmtN(v);
  const refetch = patch =>
    ctx.refetch({ top: vm.top, samples: vm.samples, scale, objCount: vm.objCount, cyc: cycOn, pins: vm.pins, minR: vm.minR, ...patch });
  const softWrap = s => s.replace(/\$/g, "$\u200b").replace(/([a-z])([A-Z])/g, "$1\u200b$2");
  const setPins = np => { savePins(np); refetch({ pins: np }); };

  // ---- control bar ----
  const col = document.createElement("div"); col.className = "gcol";
  const bar = document.createElement("div"); bar.className = "gtopbar";
  col.appendChild(bar);
  const seg = document.createElement("div"); seg.className = "anatseg";
  const bSample = document.createElement("button");
  bSample.textContent = `${vm.samples} sample instances`;
  const bGlobal = document.createElement("button");
  bGlobal.textContent = vm.objCount ? `× ${ctx.fmtN(vm.objCount)} instances (estimated)` : "global (count unknown)";
  bGlobal.disabled = !vm.objCount;
  const syncSeg = () => { bSample.classList.toggle("on", !G()); bGlobal.classList.toggle("on", G()); };
  bSample.onclick = () => { scale = "sample"; syncSeg(); refreshSide(); };
  bGlobal.onclick = () => { if (vm.objCount) { scale = "global"; syncSeg(); refreshSide(); } };
  syncSeg();
  seg.appendChild(bSample); seg.appendChild(bGlobal);
  bar.appendChild(seg);
  if (vm.available.length > 1) {
    const hint = document.createElement("span"); hint.className = "hint"; hint.textContent = "samples";
    bar.appendChild(hint);
    const kseg = document.createElement("div"); kseg.className = "anatseg";
    for (const k of vm.available) {
      const b = document.createElement("button");
      b.textContent = `${k}`;
      b.classList.toggle("on", k === vm.samples);
      b.onclick = () => refetch({ samples: k });
      kseg.appendChild(b);
    }
    bar.appendChild(kseg);
  }
  const hintTop = document.createElement("span"); hintTop.className = "hint"; hintTop.textContent = "top";
  const rng = document.createElement("input");
  rng.type = "range"; rng.className = "gtop";
  rng.min = Math.min(MIN_TOP, vm.maxNodes); rng.max = vm.maxNodes; rng.value = vm.top;
  const rngV = document.createElement("span"); rngV.className = "hint"; rngV.textContent = vm.top;
  rng.oninput = () => { rngV.textContent = rng.value; };
  rng.onchange = () => refetch({ top: +rng.value });
  const hintMin = document.createElement("span"); hintMin.className = "hint"; hintMin.textContent = "min retained";
  const minIn = document.createElement("input");
  minIn.className = "gpinin gminr"; minIn.placeholder = "off (e.g. 5m)"; minIn.spellcheck = false;
  minIn.value = vm.minR ? ctx.fmtB(vm.minR) : "";
  minIn.title = "hide classes with less retained heap than this (k/m/g suffixes)";
  const minSt = document.createElement("span"); minSt.className = "hint";
  const applyMin = () => {
    const v = minIn.value.trim();
    if (!v) { if (vm.minR) refetch({ minR: 0 }); return; }
    const b = parseBytes(v);
    if (b === null) { minSt.textContent = "not a size — try 5m / 500k / 1.5g"; return; }
    if (b !== vm.minR) refetch({ minR: b });
  };
  minIn.addEventListener("change", applyMin);
  minIn.addEventListener("keydown", e => { if (e.key === "Enter") applyMin(); });
  const hint = document.createElement("span"); hint.className = "hint";
  hint.textContent = "scroll = pan · ctrl/shift+scroll = zoom (+/− keys, 0 = fit) · edges point down from the " +
    "inspected class · faint dashed = class-level cycle edge, points up (⟲ toggles) · small circles inside a " +
    "node = nested classes holding a back-ref to it · right column = pinned common classes · " +
    "zoom in for more labels · click = detail + highlight + pin/unpin · double-click = open class";
  bar.appendChild(hintTop); bar.appendChild(rng); bar.appendChild(rngV);
  bar.appendChild(hintMin); bar.appendChild(minIn); bar.appendChild(minSt);
  bar.appendChild(hint);

  // ---- pin editor: chips for every pinned class + free-form add ----
  const pinbar = document.createElement("div"); pinbar.className = "gpinbar";
  const present = new Set(N.filter(nd => !nd.dropped).map(nd => nd.cls));
  const pinLab = document.createElement("span"); pinLab.className = "hint";
  pinLab.textContent = "pinned:";
  pinbar.appendChild(pinLab);
  for (const p of vm.pins) {
    const chip = document.createElement("span");
    chip.className = "gpin" + (present.has(p) ? "" : " absent");
    chip.title = p + (present.has(p) ? "" : " (not in this graph)");
    chip.appendChild(document.createTextNode(ctx.shortClass(p)));
    const x = document.createElement("button");
    x.textContent = "×"; x.title = "unpin " + p;
    x.addEventListener("click", () => setPins(vm.pins.filter(q => q !== p)));
    chip.appendChild(x);
    pinbar.appendChild(chip);
  }
  const pinIn = document.createElement("input");
  pinIn.className = "gpinin"; pinIn.placeholder = "pin a class (full name)…";
  pinIn.spellcheck = false;
  const pinAdd = document.createElement("button");
  pinAdd.className = "gpinadd"; pinAdd.textContent = "add";
  const pinStatus = document.createElement("span"); pinStatus.className = "hint";
  const addPin = () => {
    const v = pinIn.value.trim();
    if (!v) return;
    if (vm.pins.includes(v)) { pinStatus.textContent = "already pinned"; return; }
    setPins([...vm.pins, v]);
  };
  pinAdd.addEventListener("click", addPin);
  pinIn.addEventListener("keydown", e => { if (e.key === "Enter") addPin(); });
  const pinReset = document.createElement("button");
  pinReset.className = "gpinadd"; pinReset.textContent = "reset";
  pinReset.title = "back to the default pin set";
  pinReset.addEventListener("click", () => setPins([...DEFAULT_PINS]));
  pinbar.appendChild(pinIn); pinbar.appendChild(pinAdd); pinbar.appendChild(pinReset);
  pinbar.appendChild(pinStatus);
  col.appendChild(pinbar);

  // ---- canvas + side panel ----
  const wrap = document.createElement("div"); wrap.className = "gwrap";
  const cwrap = document.createElement("div"); cwrap.className = "gcanvas-wrap";
  const svg = document.createElementNS(SVGNS, "svg"); svg.setAttribute("class", "gcanvas");
  const tools = document.createElement("div"); tools.className = "gtools";
  const side = document.createElement("div"); side.className = "gside";
  cwrap.appendChild(svg); cwrap.appendChild(tools);
  wrap.appendChild(cwrap); wrap.appendChild(side);
  col.appendChild(wrap);
  container.appendChild(col);

  const W = () => svg.clientWidth || 760, H = () => svg.clientHeight || 620;   // live: window may resize

  // ---- defs: fixed-size arrowheads (userSpaceOnUse — must NOT scale with edge width) ----
  const defs = document.createElementNS(SVGNS, "defs");
  svg.appendChild(defs);
  for (const [id, cls] of [["arr", "gmark"], ["arr-in", "gmark in"], ["arr-out", "gmark out"], ["arr-bi", "gmark bi"]]) {
    const m = document.createElementNS(SVGNS, "marker");
    m.setAttribute("id", id); m.setAttribute("viewBox", "0 0 8 8");
    m.setAttribute("refX", 7); m.setAttribute("refY", 4);
    m.setAttribute("markerWidth", 7); m.setAttribute("markerHeight", 7);
    m.setAttribute("markerUnits", "userSpaceOnUse");
    m.setAttribute("orient", "auto-start-reverse");
    const p = document.createElementNS(SVGNS, "path");
    p.setAttribute("d", "M 0 0 L 8 4 L 0 8 z");
    p.setAttribute("class", cls);
    m.appendChild(p); defs.appendChild(m);
  }
  const vp = document.createElementNS(SVGNS, "g");
  svg.appendChild(vp);
  const mk = (t, at) => { const e = document.createElementNS(SVGNS, t); for (const k in at) e.setAttribute(k, at[k]); vp.appendChild(e); return e; };
  const setMarkers = (el, e, id) => {
    if (e.aT) el.setAttribute("marker-end", `url(#${id})`); else el.removeAttribute("marker-end");
    if (e.aS) el.setAttribute("marker-start", `url(#${id})`); else el.removeAttribute("marker-start");
  };
  const edgeEls = E.map(e => {
    if (e.d === null) return null;
    const el = mk("path", { d: e.d, "class": "gedge" + (e.cyc ? " cyc" : ""),
                            "stroke-width": (e.cyc ? 1 : e.w).toFixed(2) });
    setMarkers(el, e, "arr");
    const ti = document.createElementNS(SVGNS, "title");
    ti.textContent = e.tip; el.appendChild(ti);
    return el;
  });
  // column separator + caption
  if (layout.colCount) {
    mk("line", { x1: layout.colX - COL_SEP_DX, y1: -8, x2: layout.colX - COL_SEP_DX,
                 y2: layout.height + 8, "class": "glane-sep" });
    const cap = mk("text", { x: layout.colX, y: -2, "text-anchor": "middle", "class": "glabel" });
    cap.textContent = "pinned";
  }
  // self-loops: arc hugging the node's top-right edge; shared ring: held by ≥3 classes
  N.forEach((nd, u) => {
    if (nd.nestedIn !== -1 || nd.dropped) return;
    if (nd.self) mk("circle", { cx: nd.x + nd.rad * 0.72, cy: nd.y - nd.rad * 0.72,
                                r: Math.max(3, nd.rad * 0.3), "class": "gself" });
    if (nd.shared) mk("circle", { cx: nd.x, cy: nd.y, r: nd.rad + 2.6, "class": "gshared" });
  });
  const circles = N.map((nd, u) => {
    if (nd.nestedIn !== -1 || nd.dropped) return null;
    return mk("circle", { cx: nd.x, cy: nd.y, r: nd.rad, fill: ctx.catColor(nd.cat),
                          "class": "gnode" + (u === layout.rootTop ? " froot" : "") +
                                   (nd.r < layout.rmax * 0.005 ? " tiny" : "") });
  });
  // nested circles ride on top of their host; "+n" badge when capped
  N.forEach((nd, u) => {
    if (!nd.nest.length && !nd.nestMore) return;
    for (const m of nd.nest) {
      const mn = N[m];
      const c = mk("circle", { cx: mn.x, cy: mn.y, r: mn.rad, fill: ctx.catColor(mn.cat), "class": "fnest" });
      const ti = document.createElementNS(SVGNS, "title");
      ti.textContent = `${ctx.shortClass(mn.cls)} — nested in ${ctx.shortClass(nd.cls)} ` +
        `(holds a back-reference to it)\n${ctx.fmtN(mn.n)} objects · ${ctx.fmtB(mn.s)} shallow · ${ctx.fmtB(mn.r)} retained`;
      c.appendChild(ti);
      c.addEventListener("click", e => { if (!moved) { showNode(m); e.stopPropagation(); } });
      c.addEventListener("dblclick", e => { ctx.onOpenViz("anatomy", vm.dumpId, mn.cls); e.stopPropagation(); });
    }
    if (nd.nestMore) {
      const t = mk("text", { x: nd.x, y: nd.y + nd.rad - 3, "text-anchor": "middle", "class": "glabel" });
      t.textContent = `+${nd.nestMore} nested`;
    }
  });
  const selRing = mk("circle", { "class": "gsel", display: "none" });   // selection marker, on top

  // ---- labels: screen-space overlay, measured, collision-free, zoom-revealed ----
  const labG = document.createElementNS(SVGNS, "g");
  svg.appendChild(labG);
  const mctx = document.createElement("canvas").getContext("2d");
  mctx.font = "11px system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif";   // must match .glabel
  const wcache = new Map();
  const tw = s => { let w = wcache.get(s); if (w === undefined) { w = mctx.measureText(s).width; wcache.set(s, w); } return w; };
  const rankOrd = [...N.keys()].filter(u => N[u].nestedIn === -1 && !N[u].dropped).sort((a, b) => N[b].r - N[a].r);
  const rankOf = new Map(rankOrd.map((u, i) => [u, i]));
  let zm, labRAF = 0;
  const drawLabels = () => {
    labRAF = 0;
    labG.textContent = "";
    const placed = [];
    // the selected node labels first and bright, its neighbors normal, the rest dimmed
    const ord = shown >= 0 && N[shown].nestedIn === -1 && !N[shown].dropped
      ? [shown, ...rankOrd.filter(u => u !== shown)] : rankOrd;
    for (const u of ord) {
      const nd = N[u];
      const sx = nd.x * zm.k + zm.x, sy = nd.y * zm.k + zm.y, sr = nd.rad * zm.k;
      if (sx < -60 || sx > W() + 60 || sy < -24 || sy > H() + 24) continue;
      if (u !== shown && sr < 5 && rankOf.get(u) >= LABEL_RANK_MAX) continue;   // small nodes label themselves when zoomed in
      const name = vm.labels[u];
      const w = tw(name) + 2;
      for (const c of [{ x: sx + sr + 5, y: sy + 3.5, a: "start" }, { x: sx - sr - 5, y: sy + 3.5, a: "end" }, { x: sx, y: sy + sr + 12, a: "middle" }]) {
        const x0 = c.a === "start" ? c.x : c.a === "end" ? c.x - w : c.x - w / 2;
        if (x0 < 2 || x0 + w > W() - 2) continue;
        const r = { x0, y0: c.y - 9, x1: x0 + w, y1: c.y + 2.5 };
        if (placed.some(q => r.x0 < q.x1 && q.x0 < r.x1 && r.y0 < q.y1 && q.y0 < r.y1)) continue;
        placed.push(r);
        const t = document.createElementNS(SVGNS, "text");
        t.setAttribute("x", c.x); t.setAttribute("y", c.y);
        t.setAttribute("text-anchor", c.a);
        t.setAttribute("class", "glabel" + (shown === -1 ? "" : u === shown ? " on" : nb.has(u) ? "" : " dim"));
        t.textContent = name; labG.appendChild(t);
        break;
      }
    }
  };
  const queueLabels = () => { if (!labRAF) labRAF = requestAnimationFrame(drawLabels); };

  // ---- viewport transform: scroll pans, ctrl/shift+scroll zooms, keys +/- and 0 ----
  const apply = () => { vp.setAttribute("transform", `translate(${zm.x},${zm.y}) scale(${zm.k})`); queueLabels(); };
  const fit = () => {
    zm = { k: Math.min(1, (W() - 40) / Math.max(1, layout.width)), x: 0, y: 14 };
    zm.x = (W() - layout.width * zm.k) / 2;
    apply();
  };
  const zoomAt = (mx, my, dk) => {
    const k2 = Math.min(10, Math.max(0.1, zm.k * dk));
    zm.x = mx - (mx - zm.x) * (k2 / zm.k); zm.y = my - (my - zm.y) * (k2 / zm.k); zm.k = k2;
    apply();
  };
  fit();
  svg.addEventListener("wheel", e => {
    e.preventDefault();
    if (e.ctrlKey || e.shiftKey) {
      const rc = svg.getBoundingClientRect();
      zoomAt(e.clientX - rc.left, e.clientY - rc.top, e.deltaY < 0 ? 1.18 : 1 / 1.18);
    } else {
      zm.x -= e.deltaX; zm.y -= e.deltaY;
      apply();
    }
  }, { passive: false });
  // drag pan: window-level tracking, no pointer capture (that killed node clicks);
  // listeners self-remove once the svg is detached (the popup owns no cleanup hook)
  let drag = null, moved = false;
  svg.addEventListener("pointerdown", e => {
    drag = { x: e.clientX, y: e.clientY, zx: zm.x, zy: zm.y };
    moved = false;
  });
  const onMove = e => {
    if (!svg.isConnected) { window.removeEventListener("pointermove", onMove); return; }
    if (!drag) return;
    const dx = e.clientX - drag.x, dy = e.clientY - drag.y;
    if (!moved && Math.abs(dx) + Math.abs(dy) > 5) { moved = true; svg.classList.add("panning"); }
    if (moved) { zm.x = drag.zx + dx; zm.y = drag.zy + dy; apply(); }
  };
  const onUp = () => {
    if (!svg.isConnected) { window.removeEventListener("pointerup", onUp); return; }
    drag = null; svg.classList.remove("panning");
  };
  const onKey = e => {
    if (!svg.isConnected) { window.removeEventListener("keydown", onKey); return; }
    if (e.key === "+" || e.key === "=") zoomAt(W() / 2, H() / 2, 1.25);
    else if (e.key === "-" || e.key === "_") zoomAt(W() / 2, H() / 2, 0.8);
    else if (e.key === "0") fit();
  };
  window.addEventListener("pointermove", onMove);
  window.addEventListener("pointerup", onUp);
  window.addEventListener("keydown", onKey);
  const tBtn = (txt, title, cls, fn) => {
    const b = document.createElement("button");
    b.textContent = txt; b.title = title;
    if (cls) b.className = cls;
    b.onclick = fn;
    tools.appendChild(b);
    return b;
  };
  tBtn("−", "zoom out (−)", "", () => zoomAt(W() / 2, H() / 2, 0.8));
  tBtn("+", "zoom in (+)", "", () => zoomAt(W() / 2, H() / 2, 1.25));
  tBtn("fit", "fit to view (0)", "fit", fit);
  // cycle-edge toggle
  if (layout.cycleCount) {
    const setCyc = on => svg.classList.toggle("nocyc", !on);
    const b = tBtn("⟲ " + layout.cycleCount,
      "class-level cycle edges (real back-references: listener registrations, owner refs, …) — click to hide/show",
      "fit", () => { cycOn = !cycOn; setCyc(cycOn); b.classList.toggle("off", !cycOn); });
    if (!cycOn) { setCyc(false); b.classList.add("off"); }
  }

  // ---- node inspection ----
  const liveCount = N.filter(n => !n.dropped).length;
  const defaultSide = () =>
    `<div class="gdim">${liveCount} classes (top by retained) · ${E.length} connections · ${layout.layerCount} layers` +
    `${layout.colCount ? ` · ${layout.colCount} pinned in the right column` : ""}` +
    `${layout.nestCount ? ` · ${layout.nestCount} nested into their owners` : ""}` +
    `${layout.cycleCount ? ` · ${layout.cycleCount} cycle edges` : ""}` +
    `${layout.dropCount ? ` · <b>${layout.dropCount} not shown</b> — not reachable downward from the root (their holders are cut by the top filter or sit outside the retained set)` : ""}` +
    `${vm.minR ? ` · filter: ≥ ${ctx.esc(ctx.fmtB(vm.minR))} retained` : ""}` +
    `${G() ? ` · numbers extrapolated per-instance × ${ctx.esc(ctx.fmtN(vm.objCount))}` : ""}.<br><br>
    <b>Read it:</b> the inspected class sits on top; each layer is what the layers above hold; edges only
    point down — a class held by several others appears once, with edges converging into it.
    <b>Node size = the class's TOTAL retained bytes in the whole retained set</b> — a child can be bigger
    than its parent: held by many others (shared). Purple ring = shared (≥3 holder classes).
    ⇄ = two-way class-level reference (drawn downward, arrowheads both ends); ↻ arc = self-reference.
    A small circle inside a node = a nested class (inner class, lambda) whose instances hold a back-reference
    to the outer one — the capture edge is implied by containment, not drawn.
    Right column = pinned common classes; edges to them route right and they break the top-down rule by
    design. Faint dashed = class-level cycle edge (points up) — hide it with ⟲.<br><br>
    Click a node for its holder/held breakdown and to pin/unpin it.</div>`;
  const resetEdges = () => edgeEls.forEach((el, ri) => {
    if (!el) return;
    const e = E[ri];
    el.setAttribute("class", "gedge" + (e.cyc ? " cyc" : ""));
    el.setAttribute("stroke-width", (e.cyc ? 1 : e.w).toFixed(2));
    setMarkers(el, e, "arr");
  });
  const showNode = u => {
    shown = u;
    const d = vm.details[u], nd = N[u];
    const pctR = vm.setBytes && d.r ? (() => { const p = 100 * d.r / vm.setBytes;
      return p >= 0.05 ? `${p >= 10 ? p.toFixed(0) : p.toFixed(1)}% of the retained set` : "<0.1% of the retained set"; })() : "";
    const row = l => `<div class="gref"><div class="gref-cls" title="${ctx.esc(l.cls)}">${softWrap(ctx.esc(l.short))}</div>` +
      `<div class="gref-meta"><span class="f" title="${ctx.esc(l.f)}">${softWrap(ctx.esc(l.f))}</span>` +
      `<span class="n">×${ctx.esc(ctx.fmtN(l.n))} · ${ctx.esc(fxB(l.b))}</span></div></div>`;
    const where = d.col ? "pinned column · outbound refs listed, not drawn · "
      : d.nestedIn >= 0 ? `nested in ${ctx.esc(vm.details[d.nestedIn].short)} (back-ref) · ` : "";
    const pinBtn = d.col ? `<button class="gpinb" data-act="unpin">unpin from column</button>`
      : d.nestedIn < 0 && u !== layout.rootTop ? `<button class="gpinb" data-act="pin">pin to column</button>` : "";
    side.innerHTML = `<h5>${ctx.esc(d.short)}</h5>
      <div class="gfull">${ctx.esc(d.cls)}</div>
      <div class="gmeta">
        <div class="gmrow"><span>retained</span><span class="v"><b>${ctx.esc(fxB(d.r))}</b>${pctR ? ` · ${ctx.esc(pctR)}` : ""}</span></div>
        <div class="gmrow"><span>shallow</span><span class="v">${ctx.esc(fxB(d.s))}</span></div>
        <div class="gmrow"><span>objects</span><span class="v">${ctx.esc(fxN(d.n))}${G() ? " (est.)" : ""}</span></div>
      </div>
      <div class="gmeta gdim">${where}layer ${d.layer} · held by ${d.ins.length} class${d.ins.length !== 1 ? "es" : ""} · holds ${d.outs.length}${d.nestCount ? ` · ${d.nestCount} nested inside` : ""}</div>
      <div class="gopenrow"><button class="pri gopen">open class ▸</button>${pinBtn}</div>
      <div class="glab">held by (inbound refs — shared when many)</div><div class="grefs">${d.ins.map(row).join("") || '<div class="gdim">—</div>'}</div>
      <div class="glab">holds (outbound refs)</div><div class="grefs">${d.outs.map(row).join("") || '<div class="gdim">—</div>'}</div>`;
    side.querySelector(".gopen").onclick = () => ctx.onOpenViz("anatomy", vm.dumpId, d.cls);
    const pb = side.querySelector(".gpinb");
    if (pb) pb.onclick = () =>
      setPins(pb.dataset.act === "pin" ? [...vm.pins, d.cls] : vm.pins.filter(x => x !== d.cls));
    selRing.setAttribute("cx", nd.x); selRing.setAttribute("cy", nd.y);
    selRing.setAttribute("r", nd.rad + 4); selRing.removeAttribute("display");
    nb = new Set();
    edgeEls.forEach((el, ri) => {
      if (!el) return;
      const e = E[ri];
      const isS = e.s === u || e.os === u || (e.bi && e.ros === u);
      const isT = e.t === u || e.ot === u || (e.bi && e.rot === u);
      if (!isS && !isT) {
        el.setAttribute("class", "gedge dim" + (e.cyc ? " cyc" : ""));
        el.setAttribute("stroke-width", (e.cyc ? 1 : e.w).toFixed(2));
        setMarkers(el, e, "arr");
        return;
      }
      nb.add(e.s); nb.add(e.t);
      const ek = e.bi ? "bi" : (isT ? "in" : "out");
      el.setAttribute("class", "gedge " + ek + (e.cyc ? " cyc" : ""));
      el.setAttribute("stroke-width", (1.2 + 3.3 * Math.sqrt(e.b / layout.emax)).toFixed(2));
      setMarkers(el, e, "arr-" + ek);
    });
    queueLabels();
  };
  const refreshSide = () => { if (shown >= 0 && vm.details[shown]) showNode(shown); else side.innerHTML = defaultSide(); };
  refreshSide();
  circles.forEach((c, u) => {
    if (!c) return;
    c.addEventListener("click", e => { if (!moved) { showNode(u); e.stopPropagation(); } });
    c.addEventListener("dblclick", e => { ctx.onOpenViz("anatomy", vm.dumpId, N[u].cls); e.stopPropagation(); });
  });
  svg.addEventListener("click", e => {
    if (e.target === svg && !moved) {
      shown = -1; nb = new Set();
      selRing.setAttribute("display", "none");
      resetEdges(); side.innerHTML = defaultSide(); queueLabels();
    }
  });
}
