/* Reference graph viz: class-level reference DAG over the union retained set,
   fed by the anatomy-v2 payload (GET /api/dumps/{id}/anatomy?class=…&v=2&samples=…;
   payload shape
   {samples, available, graph:{nodes:[[cls,n,shallow,retained]], links:[[s,t,field,n,bytes]]}}).
   Layout is split from rendering: computeLayout() is pure (no DOM, no fetch) and
   unit-testable in node; prepare() fetches + shapes the viewModel; render() only
   draws the viewModel. Layout rationale (value lane, cycle edges, pair merging)
   is documented at computeLayout(). */

import { fmtB, fmtN, catOf, shortClass, scaleFactor } from "./common.js";
import { findClassStats } from "./anatomy.js";

export const kind = "graph";

const SVGNS = "http://www.w3.org/2000/svg"; // namespace for createElementNS
const DEFAULT_TOP = 140;   // top-N classes (by retained bytes) kept in the layout
const MIN_TOP = 30;        // floor of the top-N slider
const LAYERH = 78;         // vertical pitch between layers, px
const SPX = 26;            // horizontal gap between adjacent node edges in a layer, px
const RAD_BASE = 5;        // node radius = RAD_BASE + RAD_SPAN*sqrt(retained/rmax)
const RAD_SPAN = 15;       // …sqrt-scaled so areas stay comparable across dumps
const LANE_DX = 130;       // x distance of the value-type lane from the main layout
const LANE_SEP_DX = 75;    // lane separator line sits this far left of the lane
const SWEEPS = 8;          // barycenter sweep iterations (one down+up pass each)
const DETAIL_ROWS = 14;    // max inbound/outbound rows in the click detail panel
const SHARED_MIN = 3;      // ≥ this many holder classes earns the "shared" ring
const LABEL_RANK_MAX = 28; // small nodes past this retained-rank label only when zoomed
const LANE_RE = /^(byte|char|short|int|long|float|double|boolean)\[\]$/; // value-lane primitive arrays

/* Pure layout: graph = {nodes:[[cls,n,shallow,retained]], links:[[s,t,field,n,bytes]]},
   opts = {top, root} (root = inspected class, never pulled into the value lane).
   Returns {nodes, edges, positions, width, height, laneX, laneCount, layerCount,
   cycleCount, emax, rmax, inL, outL}. No DOM, no fetch, no input mutation. */
export function computeLayout(graph, opts = {}) {
  const g = graph;
  const topN = Math.min(opts.top ?? DEFAULT_TOP, g.nodes.length);
  const root = opts.root ?? null;

  // ---- top-N classes by retained ----
  const keepIdx = [...g.nodes.keys()].sort((i, j) => g.nodes[j][3] - g.nodes[i][3]).slice(0, topN);
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
  const D = [...pmap.values()];   // directed pair edges (fields summed, self-loops excluded)
  const pIn = N.map(() => []), pOut = N.map(() => []);
  D.forEach((e, ei) => { pOut[e.s].push(ei); pIn[e.t].push(ei); });

  // ---- cycle break (DFS from biggest: edges to on-stack nodes are back edges) ----
  const color = N.map(() => 0), back = new Set();
  const starts = [...N.keys()].sort((a, b) => N[b].r - N[a].r);
  for (const s0 of starts) {
    if (color[s0]) continue;
    color[s0] = 1;
    const st = [[s0, 0]];
    while (st.length) {
      const cur = st[st.length - 1], u = cur[0];
      if (cur[1] < pOut[u].length) {
        const ei = pOut[u][cur[1]++], v = D[ei].t;
        if (color[v] === 0) { color[v] = 1; st.push([v, 0]); }
        else if (color[v] === 1) back.add(ei);
      } else { color[u] = 2; st.pop(); }
    }
  }

  // ---- merge directed pairs into render edges: A⇄B collapses to one two-way edge ----
  const dirIdx = new Map(D.map((e, ei) => [e.s + "|" + e.t, ei]));
  const R = [], seenPair = new Set();
  for (let ei = 0; ei < D.length; ei++) {
    const e = D[ei], ri = dirIdx.get(e.t + "|" + e.s);
    const pk = Math.min(e.s, e.t) + "|" + Math.max(e.s, e.t);
    if (seenPair.has(pk)) continue;
    if (ri !== undefined) {   // two-way class-level reference
      seenPair.add(pk);
      const o = D[ri], aB = back.has(ei), bB = back.has(ri);
      const fwd = (aB && !bB) ? o : (bB && !aB) ? e : (e.b >= o.b ? e : o);
      R.push({ s: fwd.s, t: fwd.t, bi: true, b: Math.max(e.b, o.b), n: e.n + o.n,
               ts: e.s, tt: e.t, an: e.n, ab: e.b, bn: o.n, bb: o.b, cyc: false });
    } else {
      R.push({ s: e.s, t: e.t, bi: false, b: e.b, n: e.n, cyc: back.has(ei) });
    }
  }

  // ---- longest-path layering (ignoring back edges) ----
  const indeg = N.map(() => 0);
  D.forEach((e, ei) => { if (!back.has(ei)) indeg[e.t]++; });
  const layer = N.map(() => 0);
  const q = [], seen = new Set();
  indeg.forEach((d, i) => { if (d === 0) q.push(i); });
  while (q.length) {
    const u = q.shift(); seen.add(u);
    for (const ei of pOut[u]) {
      if (back.has(ei)) continue;
      const v = D[ei].t;
      layer[v] = Math.max(layer[v], layer[u] + 1);
      if (--indeg[v] === 0) q.push(v);
    }
  }
  for (let i = 0; i < N.length; i++) if (!seen.has(i)) layer[i] = 0;   // pure-cycle leftovers

  // ---- value lane: primitive arrays / String / Object[] are pulled out AFTER layout
  //      (their edges still layer their holders/held, so nothing floats to the top) ----
  const laneU = new Set();
  N.forEach((nd, u) => {
    if (nd.cls === root) return;
    if (LANE_RE.test(nd.cls) || nd.cls === "java.lang.String" || nd.cls === "java.lang.Object[]") laneU.add(u);
  });

  // ---- order within layers: barycenter sweeps (lane nodes included, then removed) ----
  const layersA = [];
  for (let i = 0; i < N.length; i++) { (layersA[layer[i]] = layersA[layer[i]] || []).push(i); }
  for (const ly of layersA) if (ly) ly.sort((a, b) => N[b].r - N[a].r);
  const positions = () => { const p = N.map(() => 0); layersA.forEach(ly => ly && ly.forEach((u, i) => p[u] = i)); return p; };
  const bc = (u, pos, up) => {
    const rel = up ? pOut[u].filter(ei => !back.has(ei)).map(ei => pos[D[ei].t])
                   : pIn[u].filter(ei => !back.has(ei)).map(ei => pos[D[ei].s]);
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
  for (const ly of layersA) { if (!ly) continue; const f = ly.filter(u => !laneU.has(u)); if (f.length) Ls.push(f); }

  // ---- coordinates ----
  const rmax = Math.max(...N.map(d => d.r), 1);
  const rad = d => RAD_BASE + RAD_SPAN * Math.sqrt(d.r / rmax);
  const lw = Ls.map(ly => ly.reduce((a, u) => a + 2 * rad(N[u]) + SPX, -SPX));
  const maxW = Math.max(...lw, 0);
  const totalH = Ls.length * LAYERH;
  const xy = N.map(() => ({ x: 0, y: 0 }));
  Ls.forEach((ly, d) => {
    let x = (maxW - lw[d]) / 2;
    for (const u of ly) { x += rad(N[u]); xy[u] = { x, y: d * LAYERH + LAYERH / 2 }; x += rad(N[u]) + SPX; }
  });
  const laneList = [...laneU].sort((a, b) => N[b].r - N[a].r);
  const laneX = maxW + LANE_DX;
  laneList.forEach((u, i) => { xy[u] = { x: laneX, y: totalH * (i + 0.5) / Math.max(1, laneList.length) }; });
  const laneR = laneList.length ? Math.max(...laneList.map(u => rad(N[u]))) : 0;
  const totalW = laneList.length ? laneX + laneR + 12 : maxW;

  // ---- edge geometry: cubic routes + arrowhead flags (d = null => not drawn) ----
  const emax = Math.max(...R.map(e => e.b), 1);
  const ew = e => 0.7 + 3.3 * Math.sqrt(e.b / emax);
  const edges = R.map((e, ri) => {
    const laneS = laneU.has(e.s), laneT = laneU.has(e.t);
    const out = { s: e.s, t: e.t, bi: e.bi, b: e.b, n: e.n, cyc: !!e.cyc,
                  w: +ew(e).toFixed(2), d: null, aS: false, aT: false };
    if (e.bi) { out.ts = e.ts; out.tt = e.tt; out.an = e.an; out.ab = e.ab; out.bn = e.bn; out.bb = e.bb; }
    if ((laneS && laneT) || (laneS && !e.bi)) return out;   // lane-internal / lane outbound: not drawn
    let gs = e.s, gt = e.t;
    if (laneS) { gs = e.t; gt = e.s; }                       // draw main → lane
    out.aT = (gt === e.t) || e.bi;   // arrowhead at an end iff a directed edge points into it
    out.aS = (gs === e.s) ? e.bi : true;
    const a = xy[gs], b = xy[gt], rs = rad(N[gs]), rt = rad(N[gt]);
    if (!laneS && !laneT && !out.cyc) {
      const y1 = a.y + rs + 1, y2 = b.y - rt - 1, my = (y1 + y2) / 2;
      out.d = `M ${a.x} ${y1} C ${a.x} ${my}, ${b.x} ${my}, ${b.x} ${y2}`;
    } else if (laneT) {
      const y1 = a.y + rs + 1, mx = (a.x + b.x) / 2;
      out.d = `M ${a.x} ${y1} C ${mx} ${y1}, ${mx} ${b.y}, ${b.x - rt - 1} ${b.y}`;
    } else {   // cycle back-edge: route around the right of both nodes
      const off = 20 + (ri % 4) * 9, mx = Math.max(a.x, b.x) + off;
      out.d = `M ${a.x} ${a.y - rs} C ${mx} ${a.y - 34}, ${mx} ${b.y + 34}, ${b.x} ${b.y + rt}`;
    }
    return out;
  });

  const nodes = N.map((nd, u) => ({
    oi: nd.oi, cls: nd.cls, n: nd.n, s: nd.s, r: nd.r,
    layer: layer[u], lane: laneU.has(u),
    self: inL[u].some(l => l.s === l.t),
    shared: inL[u].filter(l => l.s !== l.t).length >= SHARED_MIN,
    x: xy[u].x, y: xy[u].y, rad: rad(nd),
  }));

  return { nodes, edges, positions: xy, width: totalW, height: totalH,
           laneX, laneCount: laneList.length, layerCount: Ls.length,
           cycleCount: edges.filter(e => e.cyc && e.d).length,
           emax, rmax, inL, outL };
}

/* Data step: fetch anatomy v2 (carries the class-level reference graph) and build
   the viewModel. params = {top, samples, scale, objCount, cyc} — interactive
   controls round-trip through ctx.refetch(params), which re-runs prepare. */
export async function prepare(repo, dumpId, className, params = {}) {
  const res = await repo.anatomy(dumpId, className, { version: 2, samples: params.samples ?? null });
  if (!res.ok) {
    if (res.status === 404) return { kind, dumpId, className, notAnalyzed: true };
    return { kind, dumpId, className, error: res.error || "anatomy query failed" };
  }
  const a = res.data;
  if (!a || !a.graph || !a.graph.nodes) return { kind, dumpId, className, notAnalyzed: true };

  const maxNodes = a.graph.nodes.length;
  const top = Math.max(1, Math.min(params.top ?? DEFAULT_TOP, maxNodes));
  const layout = computeLayout(a.graph, { top, root: className });
  const nodes = layout.nodes;
  nodes.forEach(nd => { nd.cat = catOf(nd.cls); });

  const labels = nodes.map(nd => {
    const s = shortClass(nd.cls);
    return s.length > 30 ? s.slice(0, 29) + "…" : s;
  });
  const details = nodes.map((nd, u) => ({
    cls: nd.cls, short: shortClass(nd.cls), n: nd.n, s: nd.s, r: nd.r,
    layer: nd.layer, lane: nd.lane, self: nd.self, shared: nd.shared,
    ins: layout.inL[u].filter(l => l.s !== l.t).sort((x, y) => y.b - x.b).slice(0, DETAIL_ROWS)
      .map(l => ({ cls: nodes[l.s].cls, short: shortClass(nodes[l.s].cls), f: l.f, n: l.n, b: l.b })),
    outs: layout.outL[u].filter(l => l.s !== l.t).sort((x, y) => y.b - x.b).slice(0, DETAIL_ROWS)
      .map(l => ({ cls: nodes[l.t].cls, short: shortClass(nodes[l.t].cls), f: l.f, n: l.n, b: l.b })),
  }));
  layout.edges.forEach(e => {
    if (e.d === null) return;
    if (e.bi) {
      e.tip = `${shortClass(nodes[e.ts].cls)} → ${shortClass(nodes[e.tt].cls)}: ×${fmtN(e.an)}, ${fmtB(e.ab)}\n` +
              `${shortClass(nodes[e.tt].cls)} → ${shortClass(nodes[e.ts].cls)}: ×${fmtN(e.bn)}, ${fmtB(e.bb)}`;
    } else {
      e.tip = `${shortClass(nodes[e.s].cls)} → ${shortClass(nodes[e.t].cls)}: ×${fmtN(e.n)}, ${fmtB(e.b)}` +
              (e.cyc ? "\nclass-level cycle edge (points up)" : "");
    }
  });

  // extrapolation to the whole dump needs the class's instance count; if the
  // opener didn't pass it in params, look it up in the dump trees (same trick
  // as the anatomy viz) — without it global mode stays off
  let objCount = Number.isFinite(params.objCount) ? params.objCount : null;
  if (objCount === null) {
    const rt = await repo.trees(dumpId);
    const st = rt.ok ? findClassStats(rt.data.trees, className) : null;
    if (st && Number.isFinite(st.c)) objCount = st.c;
  }
  const scale = params.scale === "sample" || !objCount ? "sample" : "global";
  const factor = objCount ? scaleFactor(objCount, a.samples) : 1;

  return { kind, dumpId, className, samples: a.samples, available: a.available || [],
           objCount, scale, factor, cyc: params.cyc !== false,
           top, maxNodes, layout, labels, details };
}

/* Dumb renderer: SVG building + pan/zoom + click detail, from the viewModel only.
   Data-changing interactions (top-N, sample count) go through ctx.refetch(params). */
export function render(container, vm, ctx) {
  container.textContent = "";
  if (vm.notAnalyzed || vm.error) {
    const d = document.createElement("div");
    d.className = "gnote";
    if (vm.error) {
      d.classList.add("gerr");
      d.textContent = vm.error;
    } else {
      d.textContent = "No anatomy extracted for this class yet — the graph is built from the anatomy " +
        "extraction. Run the analysis with anatomy enabled, then reopen the graph.";
    }
    container.appendChild(d);
    return;
  }

  const layout = vm.layout, N = layout.nodes, E = layout.edges;
  let scale = vm.scale, cycOn = vm.cyc, shown = -1;
  const G = () => scale === "global";
  const fxB = v => G() ? "≈ " + ctx.fmtB(v * vm.factor) : ctx.fmtB(v);
  const fxN = v => G() ? "≈ " + ctx.fmtN(v * vm.factor) : ctx.fmtN(v);
  const refetch = patch =>
    ctx.refetch({ top: vm.top, samples: vm.samples, scale, objCount: vm.objCount, cyc: cycOn, ...patch });
  const softWrap = s => s.replace(/\$/g, "$\u200b").replace(/([a-z])([A-Z])/g, "$1\u200b$2");

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
  const hint = document.createElement("span"); hint.className = "hint";
  hint.textContent = "scroll = pan · ctrl/shift+scroll = zoom (+/− keys, 0 = fit) · ring = shared (≥3 holders) · " +
    "⇄ = two-way ref · faint dashed = class-level cycle (⟲ toggles) · right lane = value types · " +
    "zoom in for more labels · click = detail · double-click = open class";
  bar.appendChild(hintTop); bar.appendChild(rng); bar.appendChild(rngV); bar.appendChild(hint);

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
  // lane separator + caption
  if (layout.laneCount) {
    mk("line", { x1: layout.laneX - LANE_SEP_DX, y1: -8, x2: layout.laneX - LANE_SEP_DX,
                 y2: layout.height + 8, "class": "glane-sep" });
    const cap = mk("text", { x: layout.laneX, y: -2, "text-anchor": "middle", "class": "glabel" });
    cap.textContent = "value types";
  }
  // self-loops: arc hugging the node's top-right edge; shared ring: held by ≥3 classes
  N.forEach((nd, u) => {
    if (nd.self) mk("circle", { cx: nd.x + nd.rad * 0.72, cy: nd.y - nd.rad * 0.72,
                                r: Math.max(3, nd.rad * 0.3), "class": "gself" });
    if (nd.shared) mk("circle", { cx: nd.x, cy: nd.y, r: nd.rad + 2.6, "class": "gshared" });
  });
  const circles = N.map(nd =>
    mk("circle", { cx: nd.x, cy: nd.y, r: nd.rad, fill: ctx.catColor(nd.cat),
                   "class": "gnode" + (nd.r < layout.rmax * 0.005 ? " tiny" : "") }));

  // ---- labels: screen-space overlay, measured, collision-free, zoom-revealed ----
  const labG = document.createElementNS(SVGNS, "g");
  svg.appendChild(labG);
  const mctx = document.createElement("canvas").getContext("2d");
  mctx.font = "9.5px system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif";   // must match .glabel
  const wcache = new Map();
  const tw = s => { let w = wcache.get(s); if (w === undefined) { w = mctx.measureText(s).width; wcache.set(s, w); } return w; };
  const rankOrd = [...N.keys()].sort((a, b) => N[b].r - N[a].r);
  const rankOf = new Map(rankOrd.map((u, i) => [u, i]));
  let zm, labRAF = 0;
  const drawLabels = () => {
    labRAF = 0;
    labG.textContent = "";
    const placed = [];
    for (const u of rankOrd) {
      const nd = N[u];
      const sx = nd.x * zm.k + zm.x, sy = nd.y * zm.k + zm.y, sr = nd.rad * zm.k;
      if (sx < -60 || sx > W() + 60 || sy < -24 || sy > H() + 24) continue;
      if (sr < 5 && rankOf.get(u) >= LABEL_RANK_MAX) continue;   // small nodes label themselves when zoomed in
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
        t.setAttribute("text-anchor", c.a); t.setAttribute("class", "glabel");
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
      "class-level cycle edges (A holds B and B holds A at class granularity) — click to hide/show",
      "fit", () => { cycOn = !cycOn; setCyc(cycOn); b.classList.toggle("off", !cycOn); });
    if (!cycOn) { setCyc(false); b.classList.add("off"); }
  }

  // ---- node inspection ----
  const defaultSide = () =>
    `<div class="gdim">${N.length} classes (top by retained) · ${E.length} connections · ${layout.layerCount} layers` +
    `${layout.laneCount ? ` · ${layout.laneCount} value types in the right lane` : ""}` +
    `${layout.cycleCount ? ` · ${layout.cycleCount} cycle edges` : ""}` +
    `${G() ? ` · numbers extrapolated per-instance × ${ctx.esc(ctx.fmtN(vm.objCount))}` : ""}.<br><br>
    <b>Read it:</b> top = the inspected class; each layer is what the layers above hold.
    <b>Node size = the class's TOTAL retained bytes in the whole retained set</b> — a child can be
    bigger than its parent: that means it is held by many others (shared). Those are the ones to
    shrink; big nodes on thin/few inbound edges are candidates for being freed outright.
    Purple ring = shared (≥3 holder classes). ⇄ = two-way class-level reference; ↻ arc = self-reference.
    Right lane = value types (primitive arrays, String, Object[]) — everything points to them and they
    explain little; their own outbound refs are not drawn (click one to see them listed).
    Faint dashed = class-level cycle edge — a real reference that closes a cycle; hide it with the ⟲ button.<br><br>
    Click a node for its holder/held breakdown.</div>`;
  const resetEdges = () => edgeEls.forEach((el, ri) => {
    if (!el) return;
    const e = E[ri];
    el.setAttribute("class", "gedge" + (e.cyc ? " cyc" : ""));
    el.setAttribute("stroke-width", (e.cyc ? 1 : e.w).toFixed(2));
    setMarkers(el, e, "arr");
  });
  const showNode = u => {
    shown = u;
    const d = vm.details[u];
    const row = l => `<tr><td title="${ctx.esc(l.cls)}">${softWrap(ctx.esc(l.short))}</td>` +
      `<td class="num" title="${ctx.esc(l.f)}">${ctx.esc(l.f === "[]" ? "[]" : l.f.length > 16 ? l.f.slice(0, 16) + "…" : l.f)}</td>` +
      `<td class="num">×${ctx.esc(ctx.fmtN(l.n))}</td><td class="num">${ctx.esc(fxB(l.b))}</td></tr>`;
    side.innerHTML = `<h5>${ctx.esc(d.short)}</h5>
      <div class="gfull">${ctx.esc(d.cls)}</div>
      <div class="gmeta">retained <b>${ctx.esc(fxB(d.r))}</b> · shallow ${ctx.esc(fxB(d.s))} · ${ctx.esc(fxN(d.n))} objects${G() ? ' <span class="hint">(est.)</span>' : ""}</div>
      <div class="gmeta gdim">${d.lane ? "value lane · outbound refs listed, not drawn · " : ""}layer ${d.layer} · held by ${d.ins.length} class${d.ins.length !== 1 ? "es" : ""} · holds ${d.outs.length}</div>
      <div class="gopenrow"><button class="pri gopen">open class ▸</button></div>
      <div class="glab">held by (inbound refs — shared when many)</div><table>${d.ins.map(row).join("") || "<tr><td>—</td></tr>"}</table>
      <div class="glab">holds (outbound refs)</div><table>${d.outs.map(row).join("") || "<tr><td>—</td></tr>"}</table>`;
    side.querySelector(".gopen").onclick = () => ctx.onOpenViz("anatomy", vm.dumpId, d.cls);
    edgeEls.forEach((el, ri) => {
      if (!el) return;
      const e = E[ri], on = e.s === u || e.t === u;
      if (!on) {
        el.setAttribute("class", "gedge dim" + (e.cyc ? " cyc" : ""));
        el.setAttribute("stroke-width", (e.cyc ? 1 : e.w).toFixed(2));
        setMarkers(el, e, "arr");
        return;
      }
      const ek = e.bi ? "bi" : (e.t === u ? "in" : "out");
      el.setAttribute("class", "gedge " + ek + (e.cyc ? " cyc" : ""));
      el.setAttribute("stroke-width", (1.2 + 3.3 * Math.sqrt(e.b / layout.emax)).toFixed(2));
      setMarkers(el, e, "arr-" + ek);
    });
  };
  const refreshSide = () => { if (shown >= 0 && vm.details[shown]) showNode(shown); else side.innerHTML = defaultSide(); };
  refreshSide();
  circles.forEach((c, u) => {
    c.addEventListener("click", e => { if (!moved) { showNode(u); e.stopPropagation(); } });
    c.addEventListener("dblclick", e => { ctx.onOpenViz("anatomy", vm.dumpId, N[u].cls); e.stopPropagation(); });
  });
  svg.addEventListener("click", e => {
    if (e.target === svg && !moved) { shown = -1; resetEdges(); side.innerHTML = defaultSide(); }
  });
}
