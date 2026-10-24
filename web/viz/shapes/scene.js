/* Shapes viz — pure scene indexing and view-transform helpers.
   Owns dense spatial CSRs, adjacency CSRs, radius ordering and flat edge data.
   No DOM, Canvas, timers or fetch: the same indexes serve painting, bitmap
   rebuilds, hit-testing and detail views. Nothing here caps the graph. */

export const CELL = 96;
export const EDGE_BUCKETS = 64;

export function edgeWidthBucket(w) {
  return Math.max(0, Math.min(EDGE_BUCKETS - 1, Math.ceil(Math.log2(w / 0.1))));
}

/* Dense grid over allocated world cells. cellRange clamps every external
   viewport query, including views panned wholly outside the world. */
export function makeSpatialGrid(nodes, cell = CELL) {
  let gxMin = Infinity, gyMin = Infinity, gxMax = -Infinity, gyMax = -Infinity;
  for (const nd of nodes) {
    if (nd.x < gxMin) gxMin = nd.x;
    if (nd.y < gyMin) gyMin = nd.y;
    if (nd.x > gxMax) gxMax = nd.x;
    if (nd.y > gyMax) gyMax = nd.y;
  }
  if (!nodes.length) { gxMin = 0; gyMin = 0; gxMax = 0; gyMax = 0; }
  const cbx = Math.floor(gxMin / cell) - 1;
  const cby = Math.floor(gyMin / cell) - 1;
  const ncx = Math.floor(gxMax / cell) + 2 - cbx;
  const ncy = Math.floor(gyMax / cell) + 2 - cby;
  const cellCount = ncx * ncy;

  const index = (cx, cy) => {
    if (cx < cbx || cy < cby || cx >= cbx + ncx || cy >= cby + ncy) return -1;
    return (cy - cby) * ncx + (cx - cbx);
  };
  const range = (x0, y0, x1, y1, padding = 0) => {
    if (![x0, y0, x1, y1, padding].every(Number.isFinite) || x1 < x0 || y1 < y0) return null;
    const ax0 = Math.floor(x0 / cell) - padding;
    const ay0 = Math.floor(y0 / cell) - padding;
    const ax1 = Math.floor(x1 / cell) + padding;
    const ay1 = Math.floor(y1 / cell) + padding;
    const cx0 = Math.max(cbx, ax0), cy0 = Math.max(cby, ay0);
    const cx1 = Math.min(cbx + ncx - 1, ax1), cy1 = Math.min(cby + ncy - 1, ay1);
    return cx1 < cx0 || cy1 < cy0 ? null : { cx0, cy0, cx1, cy1 };
  };
  return { cell, cbx, cby, ncx, ncy, cellCount, index, range };
}

export function adjSlice(adj, u) {
  const [off, idx] = adj;
  const result = [];
  for (let i = off[u]; i < off[u + 1]; i++) result.push(idx[i]);
  return result;
}

function buildAdjacency(nodes, edges, keyOf) {
  const cnt = new Int32Array(nodes.length);
  for (let i = 0; i < edges.length; i++) cnt[keyOf(edges[i])]++;
  const off = new Int32Array(nodes.length + 1);
  for (let u = 0; u < nodes.length; u++) off[u + 1] = off[u] + cnt[u];
  const idx = new Int32Array(edges.length);
  const cur = off.slice(0, nodes.length);
  for (let i = 0; i < edges.length; i++) idx[cur[keyOf(edges[i])]++] = i;
  return [off, idx];
}

export function buildScene(layout, fills) {
  const N = layout.nodes, E = layout.edges;
  const grid = makeSpatialGrid(N);

  // nodes -> cell CSR (viewport culling + hit-testing + bitmap rebuilds)
  const nOff = new Int32Array(grid.cellCount + 1);
  for (let u = 0; u < N.length; u++)
    nOff[grid.index(Math.floor(N[u].x / CELL), Math.floor(N[u].y / CELL)) + 1]++;
  for (let c = 0; c < grid.cellCount; c++) nOff[c + 1] += nOff[c];
  const nIds = new Int32Array(N.length);
  {
    const cur = nOff.slice(0, grid.cellCount);
    for (let u = 0; u < N.length; u++)
      nIds[cur[grid.index(Math.floor(N[u].x / CELL), Math.floor(N[u].y / CELL))]++] = u;
  }

  // Radius-descending prefix for low zoom; grid culling for deep zoom.
  const byRad = new Uint32Array(N.length);
  for (let u = 0; u < N.length; u++) byRad[u] = u;
  byRad.sort((a, b) => N[b].rad - N[a].rad);
  const radOf = new Float64Array(N.length);
  for (let u = 0; u < N.length; u++) radOf[u] = N[u].rad;

  const outAdj = buildAdjacency(N, E, e => e.s);
  const inAdj = buildAdjacency(N, E, e => e.t);

  // Flat bboxes keep per-frame scans off random object loads.
  const ex0 = new Float32Array(E.length), ey0 = new Float32Array(E.length);
  const ex1 = new Float32Array(E.length), ey1 = new Float32Array(E.length);
  for (let i = 0; i < E.length; i++) {
    const e = E[i], a = N[e.s], t = N[e.t];
    ex0[i] = Math.min(a.x, t.x); ex1[i] = Math.max(a.x, t.x);
    ey0[i] = Math.min(a.y, t.y); ey1[i] = Math.max(a.y, t.y);
  }
  const ew = new Float64Array(E.length);
  for (let i = 0; i < E.length; i++) ew[i] = 0.8 + 3.4 * Math.sqrt(E[i].total / layout.emax);

  /* Register ordinary edges only in cells crossed by their chord. Edges
     spanning >32 cells are roamers, scanned against flat bboxes. */
  const eOff = new Int32Array(grid.cellCount + 1);
  let nRoam = 0;
  // Kept as two inline passes: callbacks per edge are measurable at 3.5M edges.
  for (let i = 0; i < E.length; i++) {
    const a = N[E[i].s], t = N[E[i].t];
    const cyA = Math.floor(ey0[i] / CELL), cyB = Math.floor(ey1[i] / CELL);
    const cxA = Math.floor(ex0[i] / CELL), cxB = Math.floor(ex1[i] / CELL);
    if (cyB - cyA > 32 || cxB - cxA > 32) { nRoam++; continue; }
    const ya = a.y <= t.y ? a : t, yb = a.y <= t.y ? t : a;
    const dxdy = (yb.x - ya.x) / Math.max(1e-9, yb.y - ya.y);
    for (let cy = cyA; cy <= cyB; cy++) {
      const xTop = ya.x + (Math.max(ya.y, cy * CELL) - ya.y) * dxdy;
      const xBot = ya.x + (Math.min(yb.y, (cy + 1) * CELL) - ya.y) * dxdy;
      const c0 = Math.floor(Math.min(xTop, xBot) / CELL), c1 = Math.floor(Math.max(xTop, xBot) / CELL);
      for (let cx = c0; cx <= c1; cx++) eOff[grid.index(cx, cy) + 1]++;
    }
  }
  for (let c = 0; c < grid.cellCount; c++) eOff[c + 1] += eOff[c];
  const eIds = new Int32Array(eOff[grid.cellCount]);
  const roamers = new Int32Array(nRoam);
  {
    const cur = eOff.slice(0, grid.cellCount);
    let rp = 0;
    for (let i = 0; i < E.length; i++) {
      const a = N[E[i].s], t = N[E[i].t];
      const cyA = Math.floor(ey0[i] / CELL), cyB = Math.floor(ey1[i] / CELL);
      const cxA = Math.floor(ex0[i] / CELL), cxB = Math.floor(ex1[i] / CELL);
      if (cyB - cyA > 32 || cxB - cxA > 32) { roamers[rp++] = i; continue; }
      const ya = a.y <= t.y ? a : t, yb = a.y <= t.y ? t : a;
      const dxdy = (yb.x - ya.x) / Math.max(1e-9, yb.y - ya.y);
      for (let cy = cyA; cy <= cyB; cy++) {
        const xTop = ya.x + (Math.max(ya.y, cy * CELL) - ya.y) * dxdy;
        const xBot = ya.x + (Math.min(yb.y, (cy + 1) * CELL) - ya.y) * dxdy;
        const c0 = Math.floor(Math.min(xTop, xBot) / CELL), c1 = Math.floor(Math.max(xTop, xBot) / CELL);
        for (let cx = c0; cx <= c1; cx++) eIds[cur[grid.index(cx, cy)]++] = i;
      }
    }
  }

  const colorBuckets = new Map();
  for (let u = 0; u < N.length; u++) {
    let bucket = colorBuckets.get(fills[u]);
    if (!bucket) colorBuckets.set(fills[u], bucket = []);
    bucket.push(u);
  }
  const spans = [0, 0, 0, 0, 0, 0, 0];
  for (let i = 0; i < E.length; i++) {
    const sy = Math.floor(ey1[i] / CELL) - Math.floor(ey0[i] / CELL);
    const sx = Math.floor(ex1[i] / CELL) - Math.floor(ex0[i] / CELL);
    const span = Math.max(sx, sy);
    spans[span <= 1 ? 0 : span <= 2 ? 1 : span <= 4 ? 2 : span <= 8 ? 3 : span <= 16 ? 4 : span <= 32 ? 5 : 6]++;
  }

  let stamp = 0;
  const eStamp = new Int32Array(E.length);
  const nextEdgeStamp = () => {
    stamp++;
    if (stamp >= 2147483647) { eStamp.fill(0); stamp = 1; }
    return stamp;
  };
  return { layout, fills, grid, nOff, nIds, byRad, radOf, outAdj, inAdj,
    ex0, ey0, ex1, ey1, ew, eOff, eIds, roamers, eStamp, nextEdgeStamp,
    colorBuckets, spans };
}

export function hitTest(scene, wx, wy) {
  const range = scene.grid.range(wx, wy, wx, wy, 1);
  if (!range) return -1;
  let best = -1, bestD = Infinity;
  for (let cx = range.cx0; cx <= range.cx1; cx++) {
    for (let cy = range.cy0; cy <= range.cy1; cy++) {
      const c = scene.grid.index(cx, cy);
      for (let p = scene.nOff[c]; p < scene.nOff[c + 1]; p++) {
        const u = scene.nIds[p], nd = scene.layout.nodes[u];
        const d = Math.hypot(nd.x - wx, nd.y - wy);
        if (d <= nd.rad && d < bestD) { best = u; bestD = d; }
      }
    }
  }
  return best;
}

export function fitTransform(viewW, viewH, worldW, worldH) {
  const w = Math.max(1, worldW), h = Math.max(1, worldH);
  const k = Math.max(1e-9, Math.min(1.4, (viewW - 40) / w, (viewH - 40) / h));
  return { k, x: (viewW - worldW * k) / 2, y: (viewH - worldH * k) / 2 };
}

export function zoomTransform(zm, mx, my, dk, viewW, viewH, worldW, worldH) {
  if (!Number.isFinite(dk) || dk <= 0) return { ...zm };
  const minK = Math.max(1e-9, 0.9 * Math.min((viewW - 40) / Math.max(1, worldW),
    (viewH - 40) / Math.max(1, worldH)));
  const k = Math.min(64, Math.max(zm.k * dk, minK));
  return { k, x: mx - (mx - zm.x) * (k / zm.k), y: my - (my - zm.y) * (k / zm.k) };
}
