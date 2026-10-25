/* Shapes viz — layout: computeShapesLayout (pure: no DOM, no fetch, no input
   mutation). The shape graph is a DAG (the condensation of the reachable
   object graph — cycles live INSIDE scc shapes as internalEdges), so layering
   is exact longest-path from the sources (shapes nothing points to).
   Scale: runs over the FULL payload (worst real case: 1.37M shapes, 3.5M
   edges — FlatSourceDirectoriesImpl), so EVERYTHING here is iterative:
   no array spreads into Math.max (Firefox: "too many function arguments",
   Chrome: "Maximum call stack size exceeded") and no recursive DFS (the
   longest path can be tens of thousands of levels).
   See index.js for the module map. */

import { computeSccLayout } from "./scc-layout.js";

const R_MIN = 7, R_MAX = 42;      // node radius clamp
const X_GAP = 30;                 // horizontal clearance between node edges
const Y_GAP = 76;                 // vertical clearance between layers
const ROW_GAP = 56;               // vertical clearance between wrapped rows of one layer
const DAG_EXTENT_LIMIT = 100000;  // auto-compact beyond this natural width/height

const METRIC = {
  shallow: s => s.st,
  count: s => s.count,
  roots: s => s.roots,
};

/* nodes: [{id,label,kind,count,st,smin,smax,roots,sccSize,internalEdges,
            edgesPerMember,cat}], edges: [[s, t, multPerMember]].
   metric: "shallow" | "count" | "roots".
   layoutMode: "auto" | "dag" | "compact". Auto uses the natural top-down
   layout unless its unwrapped world exceeds DAG_EXTENT_LIMIT in either axis.
   Returns {nodes: [...+{rad,x,y,layer}], edges: [{s,t,mult,total,mx,my}],
            width, height, layerCount, layoutMode}. Inputs are not mutated. */
export function computeShapesLayout(nodesIn = [], edgesIn = [], metric = "shallow",
                                    layoutMode = "auto") {
  if (!Array.isArray(nodesIn) || !Array.isArray(edgesIn))
    throw new TypeError("shapes layout expects node and edge arrays");
  const mv = METRIC[metric] || METRIC.shallow;
  const requestedMode = layoutMode === "dag" || layoutMode === "compact" ? layoutMode : "auto";
  const N = nodesIn.map(s => ({ ...s }));
  if (!N.length)
    return { nodes: N, edges: [], width: 0, height: 0, layerCount: 0, emax: 1,
      layoutMode: requestedMode === "compact" ? "compact" : "dag" };

  // radii by metric (iterative max — the payload can be >1M nodes)
  const mvals = N.map(mv);
  let mmax = 1;
  for (let i = 0; i < mvals.length; i++) if (mvals[i] > mmax) mmax = mvals[i];
  N.forEach((nd, u) => {
    nd.rad = Math.max(R_MIN, Math.min(R_MAX, R_MIN + (R_MAX - R_MIN) * Math.sqrt(mvals[u] / mmax)));
    nd.hw = nd.rad; nd.hh = nd.rad;
    if (nd.sccComplete) {
      nd.scc = computeSccLayout(nd.members);
      nd.hw = nd.scc.width / 2; nd.hh = nd.scc.height / 2;
      // rad remains the broad-phase/LOD bound; hw/hh are exact box extents.
      nd.rad = Math.hypot(nd.hw, nd.hh);
    }
  });

  // adjacency (defensive: drop self-loops — they can't exist on the condensation)
  const E = edgesIn.filter(([s, t]) => s !== t);
  const kids = N.map(() => []), parents = N.map(() => []);
  for (const [s, t] of E) { kids[s].push(t); parents[t].push(s); }

  // ---- layers: longest-path levels from the sources, via Kahn's algorithm
  // (iterative — a recursive memoized DFS overflows the call stack on deep
  // graphs). Unreached nodes (none on a DAG, but be safe) stay at layer 0.
  const layer = new Array(N.length).fill(0);
  const indeg = new Int32Array(N.length);
  for (const [s, t] of E) indeg[t]++;
  let frontier = [];
  for (let u = 0; u < N.length; u++) if (indeg[u] === 0) frontier.push(u);
  while (frontier.length) {
    const next = [];
    for (const u of frontier) {
      const lu = layer[u] + 1;
      for (const v of kids[u]) {
        if (lu > layer[v]) layer[v] = lu;
        if (--indeg[v] === 0) next.push(v);
      }
    }
    frontier = next;
  }
  N.forEach((nd, u) => { nd.layer = layer[u]; });

  // ---- order within layers: size first, then two barycenter sweeps ----
  const byLayer = [];
  N.forEach((nd, u) => { (byLayer[nd.layer] = byLayer[nd.layer] || []).push(u); });
  for (const l of byLayer) l.sort((a, b) => mvals[b] - mvals[a]);
  const pos = new Map();   // node -> index within its layer
  byLayer.forEach(l => l.forEach((u, i) => pos.set(u, i)));   // initial, once
  const sweep = (down) => {
    const idxs = down ? [...byLayer.keys()] : [...byLayer.keys()].reverse();
    for (const li of idxs) {
      const l = byLayer[li];
      const keyed = l.map(u => {
        const rel = down ? parents[u] : kids[u];
        const bc = rel.length && pos.has(rel[0])
          ? rel.reduce((a, v) => a + (pos.get(v) ?? 0), 0) / rel.length : null;
        return [u, bc];
      });
      keyed.sort((a, b) => (a[1] === null) - (b[1] === null) || (a[1] ?? 0) - (b[1] ?? 0)
                          || mvals[b[0]] - mvals[a[0]]);
      byLayer[li] = keyed.map(k => k[0]);
      // renumber ONLY this layer — renumbering all layers per layer was
      // O(layers × nodes) and hung for minutes on deep graphs
      for (let i = 0; i < keyed.length; i++) pos.set(keyed[i][0], i);
    }
  };
  sweep(true); sweep(false); sweep(true);

  // ---- coordinates: prefer one global top-down DAG. Estimate the natural
  // one-row-per-layer bounds without allocating a second row representation.
  // Only extreme graphs use the legacy square-ish compaction: a single row
  // per layer would be ~40M px wide on a 1M-shape payload, and a 30k-layer
  // chain would be a multi-megapixel-tall strip. Forced DAG mode never folds
  // columns, but wraps rows beyond the same extent limit. ----
  let area = 0, naturalW = 0, naturalH = 0;
  for (const l of byLayer) {
    if (!l || !l.length) continue;
    let rowW = 0, rowH = 0;
    for (const u of l) {
      const nd = N[u], w = 2 * nd.hw + X_GAP, h = 2 * nd.hh;
      area += w * (h + ROW_GAP);
      rowW += w;
      if (h > rowH) rowH = h;
    }
    if (rowW - X_GAP > naturalW) naturalW = rowW - X_GAP;
    naturalH += rowH + ROW_GAP + Y_GAP;
  }
  const compact = requestedMode === "compact" || (requestedMode === "auto" &&
    (naturalW > DAG_EXTENT_LIMIT || naturalH > DAG_EXTENT_LIMIT));
  const targetW = compact ? Math.max(1200, Math.sqrt(area)) : DAG_EXTENT_LIMIT;
  // pass 1: split each layer into rows
  const layerRows = byLayer.map(l => {
    if (!l || !l.length) return [];
    const rows = [];
    let ns = [], w = 0, h = 0;
    for (const u of l) {
      const uw = 2 * N[u].hw + X_GAP;
      if (ns.length && w + uw > targetW) { rows.push({ ns, w: w - X_GAP, h }); ns = []; w = 0; h = 0; }
      if (2 * N[u].hh > h) h = 2 * N[u].hh;
      ns.push(u); w += uw;
    }
    if (ns.length) rows.push({ ns, w: w - X_GAP, h });
    return rows;
  });
  // pass 2: compact mode folds whole layers into columns of ~equal height;
  // DAG mode keeps one vertical sequence so every edge points downward.
  const layerH = layerRows.map(rows =>
    rows.length ? rows.reduce((a, r) => a + r.h + ROW_GAP, Y_GAP) : 0);
  const totalH = layerH.reduce((a, h) => a + h, 0);
  const nCols = compact ? Math.max(1, Math.round(totalH / targetW)) : 1;
  const colTarget = totalH / nCols;
  const cols = [[]];
  let acc = 0;
  byLayer.forEach((l, li) => {
    if (!layerRows[li].length) return;
    if (acc > 0 && acc + layerH[li] > colTarget && cols.length < nCols) { cols.push([]); acc = 0; }
    cols[cols.length - 1].push(li);
    acc += layerH[li];
  });
  // pass 3: assign coordinates column by column
  let x0 = 30, maxY = 30;
  for (const col of cols) {
    let colW = 0;
    for (const li of col) for (const r of layerRows[li]) if (r.w > colW) colW = r.w;
    let y = 30;
    for (const li of col) {
      for (const r of layerRows[li]) {
        let nx = x0 + Math.max(0, (colW - r.w) / 2);
        const cy = y + r.h / 2;
        for (const u of r.ns) {
          nx += N[u].hw;
          N[u].x = nx; N[u].y = cy;
          nx += N[u].hw + X_GAP;
        }
        y += r.h + ROW_GAP;
      }
      y += Y_GAP;
    }
    if (y > maxY) maxY = y;
    x0 += colW + 2 * X_GAP;
  }
  const width = x0 - X_GAP, height = maxY;

  // ---- edge paths: cubic beziers; mx,my = label anchor. emax folded into
  // the same loop (another spread-free max) ----
  let emax = 1;
  const edges = E.map(([s, t, mult]) => {
    const a = N[s], b = N[t];
    const y1 = a.y + a.hh + 2, y2 = b.y - b.hh - 4;
    const my = (y1 + y2) / 2;
    // total refs: per-member mult × instance count; per-instance totals
    // (SCC_UNIQUE and non-uniform merged SCCs) × collapsed instances
    const total = a.edgesPerMember ? mult * a.count : mult * Math.max(1, Math.floor(a.count / a.sccSize));
    if (total > emax) emax = total;
    return { s, t, mult, total, mx: (a.x + b.x) / 2, my };
  });

  return { nodes: N, edges, width, height, layerCount: byLayer.length, emax,
    layoutMode: compact ? "compact" : "dag" };
}
