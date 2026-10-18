/* Shapes viz — renderer: Canvas 2D. The SVG predecessor created one DOM
   element per node/edge and died on the full-scale payload (1.37M shapes,
   3.5M edges — FlatSourceDirectoriesImpl): layout spreads overflowed the
   call stack (Firefox: "too many function arguments"), then minutes of DOM
   churn. NOTHING is capped here — the strategy for scale is:
     - a uniform-grid spatial index over node positions → viewport culling
       for drawing and click hit-testing;
     - an OVERVIEW BITMAP (mipmap): at zooms where nodes are sub-pixel the
       whole point cloud is one drawImage (GPU); rebuilt lazily when zoom
       leaves its 3x band; live drawing takes over where culling suffices;
     - edges are width-sorted once (counting sort into log2 buckets), so a
       frame only touches edges wide enough to be visible at this zoom;
     - shape adjacency is CSR-indexed once — selection/histogram lookups
       are O(degree), not O(E) filters;
     - redraws are rAF-throttled; pan/zoom only repaints.
   The metric switch goes through ctx.refetch({metric}). See index.js for
   the module map. */

import { buildSeg } from "../common.js";

const CELL = 96;               // spatial-grid cell, world units
const TINY_R = 0.7;            // screen px: below this a node is a bitmap/batched dot
const EDGE_MIN_PX = 0.35;      // screen px: below this an edge is LOD-skipped
const LABEL_R = 14;            // screen px: node gets a text label
const EDGE_LABEL_MAX = 120;    // draw ×mult edge labels only when few are visible
const BMP_CAP = 4096;          // max scene-bitmap side, px

const C_EDGE = "rgba(74,85,104,0.5)";
const C_EDGE_DIM = "rgba(74,85,104,0.08)";
const C_EDGE_IN = "rgba(240,136,62,0.95)";
const C_EDGE_OUT = "rgba(121,184,255,0.95)";
const C_NODE_STROKE = "#0d1117";
const C_ROOT_STROKE = "#e8edf5";
const C_SEL = "#e8edf5";
const C_LABEL = "#9aa4b2";
const C_LABEL_BG = "#0a0d12";

/* "not analyzed" state: the shapes analysis has no sample sizes (it is exact
   over ALL instances), so the shared sample-picker prompt does not apply —
   one plain analyze button instead. */
function notAnalyzedPane(vm, ctx) {
  const d = document.createElement("div");
  d.className = "gnote";
  const msg = document.createElement("div");
  msg.textContent = "No shapes analysis for this class yet — the exact structural " +
    "collapse (SHAPES.md, experimental) runs over every instance of the class, " +
    "not a sample.";
  d.appendChild(msg);
  if (!ctx.analyze) {
    const hint = document.createElement("div");
    hint.className = "hint";
    hint.textContent = "Run the shapes analysis on the server, then reopen this viz.";
    d.appendChild(hint);
    return d;
  }
  const row = document.createElement("div");
  row.className = "viz-anrow";
  const btn = document.createElement("button");
  btn.textContent = "analyze (exact)";
  btn.title = "run the exact structural-collapse analysis — extracts the reachable " +
    "graph via the MAT indexes, then collapses it into shapes";
  const status = document.createElement("span");
  status.className = "viz-anstatus";
  btn.onclick = () => {
    btn.disabled = true;
    ctx.analyze((t, isErr) => {
      status.textContent = t;
      status.classList.toggle("err", !!isErr);
      if (isErr) btn.disabled = false;
    });
  };
  row.appendChild(btn);
  row.appendChild(status);
  d.appendChild(row);
  return d;
}

/* default side panel: summary + the roots histogram (the spec's headline view). */
function defaultSide(vm, ctx, outAdj) {
  const total = vm.nodes.reduce((a, n) => a + n.st, 0);
  const rootTotal = vm.rootsHistogram.reduce((a, r) => a + r[1], 0);
  let h = `<div class="gdim">${vm.shapeCount} shapes over ${ctx.esc(ctx.fmtN(vm.reachable))} ` +
    `reachable objects · ${ctx.esc(ctx.fmtB(total))} shallow · ${vm.layout.layerCount} layers.</div>` +
    `<div class="fnote">Exact: every instance of <b>${ctx.esc(vm.className)}</b> collapsed by ` +
    `structure — no sampling. Node size = ${vm.metric === "count" ? "collapsed object count" :
    vm.metric === "roots" ? "root count" : "shallow total"}; edge width = reference count ` +
    `(per-member × instances). Click a shape for details, double-click a plain class to open its flow. ` +
    `Sub-pixel detail is shown as dots until you zoom in — nothing is removed.</div>` +
    `<div class="glab">roots histogram — ${ctx.esc(ctx.fmtN(rootTotal))} instances of ${ctx.esc(ctx.shortClass(vm.className))}</div>`;
  const hmax = vm.rootsHistogram.length ? vm.rootsHistogram[0][1] : 1;
  for (const [sid, cnt] of vm.rootsHistogram.slice(0, 14)) {
    const nd = vm.nodes[sid];
    if (!nd) continue;
    const pct = Math.max(2, Math.round(100 * cnt / hmax));
    const [off, idx] = outAdj;
    const kids = [];
    const total = off[sid + 1] - off[sid];
    const KIDS_SHOWN = 12;   // the mega-SCC root has 274,598 children — the
                             // signature is a summary, the count stays exact
    for (let i = off[sid]; i < off[sid] + Math.min(total, KIDS_SHOWN); i++)
      kids.push(vm.nodes[vm.layout.edges[idx[i]].t].short);
    const sig = kids.length
      ? ` → ${kids.join(", ")}${total > KIDS_SHOWN ? `, +${(total - KIDS_SHOWN).toLocaleString("en-US")} more` : ""}`
      : "";
    h += `<div class="shhist" title="${ctx.esc(nd.label)}">` +
      `<div class="shhist-bar" style="width:${pct}%"></div>` +
      `<span class="shhist-n">${ctx.esc(ctx.fmtN(cnt))}&times;</span> ` +
      `<span class="shhist-l">${ctx.esc(nd.short)} #${sid}${ctx.esc(sig)}</span></div>`;
  }
  if (vm.rootsHistogram.length > 14)
    h += `<div class="gdim">… and ${vm.rootsHistogram.length - 14} more root shapes</div>`;
  return h;
}

/* one selected shape: the meta block (small) — the reference lists are
   mounted separately and LAZILY (hub shapes have 100-275k edges; one big
   innerHTML froze the page for minutes). Batches of 300 rows append on
   scroll — nothing is capped, everything is reachable. */
function nodeDetailHead(u, vm, ctx) {
  const N = vm.nodes;
  const nd = N[u];
  const kb = { leaf: "leaf — no children in the reachable set",
               node: "node — keyed by (class, child shapes)",
               scc: "merged SCC — collapsed cycle",
               "scc-unique": "unique SCC — too exotic to merge" }[nd.kind] || nd.kind;
  const mrow = (k, v) => `<div class="gmrow"><span>${k}</span><span class="v">${v}</span></div>`;
  return `<h5 title="${ctx.esc(nd.label)}">${ctx.esc(nd.short)} <span class="gdim">#${u}</span></h5>` +
    `<div class="gfull">${ctx.esc(nd.label)}</div>` +
    `<div class="gdim gmeta">${ctx.esc(kb)}</div>` +
    mrow("objects", ctx.esc(ctx.fmtN(nd.count)) +
      (nd.sccSize > 1 ? ` = ${ctx.esc(ctx.fmtN(Math.round(nd.count / nd.sccSize)))} instances × ${nd.sccSize}` : "")) +
    mrow("shallow total", ctx.esc(ctx.fmtB(nd.st))) +
    mrow("shallow min … max", `${ctx.esc(ctx.fmtB(nd.smin))} … ${ctx.esc(ctx.fmtB(nd.smax))}`) +
    (nd.roots ? mrow("roots", `${ctx.esc(ctx.fmtN(nd.roots))} of the queried class`) : "") +
    (nd.internalEdges ? mrow("internal edges", `${nd.internalEdges} per instance (inside the cycle)`) : "") +
    (nd.plain ? `<div class="gopenrow"><button class="gopen">open flow ▸</button></div>` : "");
}

/* lazy reference lists: append BATCH rows whenever the side panel scrolls
   near the bottom; returns a cleanup for the scroll listener */
function mountRefLists(side, u, vm, ctx, outAdj, inAdj, adjSlice) {
  const N = vm.nodes, E = vm.layout.edges;
  const row = (ei, out) => {
    const e = E[ei];
    const o = N[out ? e.t : e.s];
    return `<div class="gref"><div class="gref-cls">${ctx.esc(o.short)}</div>` +
      `<div class="gref-meta"><span class="f">${out ? "holds" : "held by"}</span>` +
      `<span class="n">×${ctx.esc(ctx.fmtN(e.mult))}${N[e.s].edgesPerMember ? " per instance" : ""} · ${ctx.esc(ctx.fmtN(e.total))} total</span></div></div>`;
  };
  const queue = [];
  for (const [title, adj, out] of [["outbound references", outAdj, true],
                                   ["inbound references", inAdj, false]]) {
    const list = adjSlice(adj, u);
    if (!list.length) continue;
    const lab = document.createElement("div"); lab.className = "glab";
    lab.textContent = `${title} — ${list.length.toLocaleString("en-US")}`;
    const box = document.createElement("div"); box.className = "grefs";
    side.appendChild(lab); side.appendChild(box);
    queue.push({ box, list, out, pos: 0 });
  }
  const BATCH = 300;
  const pump = () => {
    let budget = BATCH;
    while (budget > 0 && queue.length) {
      const q = queue[0];
      const chunk = [];
      while (budget > 0 && q.pos < q.list.length) {
        chunk.push(row(q.list[q.pos++], q.out));
        budget--;
      }
      q.box.insertAdjacentHTML("beforeend", chunk.join(""));
      if (q.pos >= q.list.length) queue.shift();
    }
    if (!queue.length) side.removeEventListener("scroll", onScroll);
  };
  const onScroll = () => {
    if (side.scrollTop + side.clientHeight > side.scrollHeight - 600) pump();
  };
  side.addEventListener("scroll", onScroll);
  pump();
  return () => side.removeEventListener("scroll", onScroll);
}

export function render(container, vm, ctx) {
  container.textContent = "";
  if (vm.notAnalyzed || vm.error) {
    if (vm.error) {
      const d = document.createElement("div");
      d.className = "gnote gerr";
      d.textContent = vm.error;
      container.appendChild(d);
    } else {
      container.appendChild(notAnalyzedPane(vm, ctx));
    }
    return;
  }

  const layout = vm.layout, N = layout.nodes, E = layout.edges;
  let shown = -1;

  // ---- shell: toolbar on top, canvas + side panel below ----
  const col = document.createElement("div"); col.className = "gcol";
  const bar = document.createElement("div"); bar.className = "fbar";
  const wrap = document.createElement("div"); wrap.className = "gwrap";
  const cwrap = document.createElement("div"); cwrap.className = "gcanvas-wrap";
  const canvas = document.createElement("canvas"); canvas.className = "gcanvas";
  const tools = document.createElement("div"); tools.className = "gtools";
  const side = document.createElement("div"); side.className = "gside";
  cwrap.appendChild(canvas); cwrap.appendChild(tools);
  wrap.appendChild(cwrap); wrap.appendChild(side);
  col.appendChild(bar); col.appendChild(wrap);
  container.appendChild(col);

  const grp = document.createElement("div"); grp.className = "fgroup";
  const lab = document.createElement("span"); lab.className = "flab"; lab.textContent = "node size";
  grp.appendChild(lab);
  grp.appendChild(buildSeg([
    { value: "shallow", label: "shallow", title: "total shallow bytes of the shape's instances" },
    { value: "count", label: "objects", title: "collapsed object count" },
    { value: "roots", label: "roots", title: "instances of the queried class" },
  ], vm.metric, v => ctx.refetch({ metric: v })));
  bar.appendChild(grp);
  const note = document.createElement("span"); note.className = "flab";
  note.textContent = `experimental — exact structural collapse, ${vm.shapeCount} shapes`;
  bar.appendChild(note);

  const g = canvas.getContext("2d");
  const W = () => canvas.clientWidth || 760, H = () => canvas.clientHeight || 620;

  // ---- one-time indexes -------------------------------------------------
  // Dense typed CSR over the cell space — the world bounds the cell count
  // (~1400²), so a couple of Int32Arrays replace every Map-of-arrays (the
  // object overhead of those OOM-crashed the renderer tab at this scale).
  let gxMin = Infinity, gyMin = Infinity, gxMax = -Infinity, gyMax = -Infinity;
  for (const nd of N) {
    if (nd.x < gxMin) gxMin = nd.x;
    if (nd.y < gyMin) gyMin = nd.y;
    if (nd.x > gxMax) gxMax = nd.x;
    if (nd.y > gyMax) gyMax = nd.y;
  }
  const CBX = Math.floor(gxMin / CELL) - 1, CBY = Math.floor(gyMin / CELL) - 1;
  const NCX = Math.floor(gxMax / CELL) + 2 - CBX, NCY = Math.floor(gyMax / CELL) + 2 - CBY;
  const ci = (cx, cy) => (cy - CBY) * NCX + (cx - CBX);

  // nodes → cell CSR (viewport culling + hit-testing + bitmap rebuilds)
  const nOff = new Int32Array(NCX * NCY + 1);
  for (let u = 0; u < N.length; u++)
    nOff[ci(Math.floor(N[u].x / CELL), Math.floor(N[u].y / CELL)) + 1]++;
  for (let c = 0; c < NCX * NCY; c++) nOff[c + 1] += nOff[c];
  const nIds = new Int32Array(N.length);
  { const cur = nOff.slice(0, NCX * NCY);
    for (let u = 0; u < N.length; u++)
      nIds[cur[ci(Math.floor(N[u].x / CELL), Math.floor(N[u].y / CELL))]++] = u; }
  const fills = N.map(nd => ctx.catColor(nd.cat));

  // nodes by radius, descending — when the overview bitmap covers the
  // sub-pixel cloud, the live pass only needs the big few (binary-searched
  // prefix), never the 1.37M-node array
  const byRad = new Uint32Array(N.length);
  for (let u = 0; u < N.length; u++) byRad[u] = u;
  byRad.sort((a, b) => N[b].rad - N[a].rad);
  const radOf = new Float64Array(N.length);
  for (let u = 0; u < N.length; u++) radOf[u] = N[u].rad;

  // shape adjacency as CSR (detail panel + histogram are O(degree))
  const csr = keyOf2 => {
    const cnt = new Int32Array(N.length);
    for (let i = 0; i < E.length; i++) cnt[keyOf2(E[i])]++;
    const off = new Int32Array(N.length + 1);
    for (let u = 0; u < N.length; u++) off[u + 1] = off[u] + cnt[u];
    const idx = new Int32Array(E.length);
    const cur = off.slice(0, N.length);
    for (let i = 0; i < E.length; i++) idx[cur[keyOf2(E[i])]++] = i;
    return [off, idx];
  };
  const outAdj = csr(e => e.s), inAdj = csr(e => e.t);
  const adjSlice = ([off, idx], u) => {
    const r = [];
    for (let i = off[u]; i < off[u + 1]; i++) r.push(idx[i]);
    return r;
  };

  // flat edge bboxes — every per-frame cull runs on typed arrays, never on
  // the edge/node objects (random object loads are cache misses ×3.5M)
  const ex0 = new Float32Array(E.length), ey0 = new Float32Array(E.length);
  const ex1 = new Float32Array(E.length), ey1 = new Float32Array(E.length);
  for (let i = 0; i < E.length; i++) {
    const e = E[i], a = N[e.s], t = N[e.t];
    ex0[i] = Math.min(a.x, t.x); ex1[i] = Math.max(a.x, t.x);
    ey0[i] = Math.min(a.y, t.y); ey1[i] = Math.max(a.y, t.y);
  }
  // edge widths precomputed + grouped into log2 buckets (counting sort) for
  // wide views, PLUS a dense cell CSR for deep zoom (where EDGE_MIN_PX/k is
  // small and the bucket scan would walk most of the 3.5M edges per frame).
  // Edges are near-vertical here (layer pitch 132px > CELL), so bbox
  // registration would smear each over ~7 rows of cells — instead each edge
  // is registered ONLY in the cells its chord actually crosses (row walk,
  // x interpolated per row). Edges spanning >32 cells (column transitions,
  // mega-SCC → everything) are "roamers", scanned linearly per frame on the
  // flat bboxes.
  const ew = new Float64Array(E.length);
  for (let i = 0; i < E.length; i++) ew[i] = 0.8 + 3.4 * Math.sqrt(E[i].total / layout.emax);
  const bucketOf = w => Math.max(0, Math.min(63, Math.ceil(Math.log2(w / 0.1))));
  const NB = 64;
  const eOff = new Int32Array(NCX * NCY + 1);
  let nRoam = 0;
  // pass 1: count
  for (let i = 0; i < E.length; i++) {
    const e = E[i], a = N[e.s], t = N[e.t];
    const cyA = Math.floor(ey0[i] / CELL), cyB = Math.floor(ey1[i] / CELL);
    const cxA = Math.floor(ex0[i] / CELL), cxB = Math.floor(ex1[i] / CELL);
    if (cyB - cyA > 32 || cxB - cxA > 32) { nRoam++; continue; }
    const ya = a.y <= t.y ? a : t, yb = a.y <= t.y ? t : a;
    const dxdy = (yb.x - ya.x) / Math.max(1e-9, yb.y - ya.y);
    for (let cy = cyA; cy <= cyB; cy++) {
      const xTop = ya.x + (Math.max(ya.y, cy * CELL) - ya.y) * dxdy;
      const xBot = ya.x + (Math.min(yb.y, (cy + 1) * CELL) - ya.y) * dxdy;
      const c0 = Math.floor(Math.min(xTop, xBot) / CELL), c1 = Math.floor(Math.max(xTop, xBot) / CELL);
      for (let cx = c0; cx <= c1; cx++) eOff[ci(cx, cy) + 1]++;
    }
  }
  for (let c = 0; c < NCX * NCY; c++) eOff[c + 1] += eOff[c];
  const eIds = new Int32Array(eOff[NCX * NCY]);
  const roamers = new Int32Array(nRoam);
  // pass 2: fill
  { const cur = eOff.slice(0, NCX * NCY);
    let rp = 0;
    for (let i = 0; i < E.length; i++) {
      const e = E[i], a = N[e.s], t = N[e.t];
      const cyA = Math.floor(ey0[i] / CELL), cyB = Math.floor(ey1[i] / CELL);
      const cxA = Math.floor(ex0[i] / CELL), cxB = Math.floor(ex1[i] / CELL);
      if (cyB - cyA > 32 || cxB - cxA > 32) { roamers[rp++] = i; continue; }
      const ya = a.y <= t.y ? a : t, yb = a.y <= t.y ? t : a;
      const dxdy = (yb.x - ya.x) / Math.max(1e-9, yb.y - ya.y);
      for (let cy = cyA; cy <= cyB; cy++) {
        const xTop = ya.x + (Math.max(ya.y, cy * CELL) - ya.y) * dxdy;
        const xBot = ya.x + (Math.min(yb.y, (cy + 1) * CELL) - ya.y) * dxdy;
        const c0 = Math.floor(Math.min(xTop, xBot) / CELL), c1 = Math.floor(Math.max(xTop, xBot) / CELL);
        for (let cx = c0; cx <= c1; cx++) eIds[cur[ci(cx, cy)]++] = i;
      }
    } }
  const eStamp = new Int32Array(E.length);
  let eStampVal = 0;
  const dbg = { roamers: roamers.length, drawnEdges: 0, labels: 0, k: 0,
                zm: null, bmpInfo: null };
  { const h = [0, 0, 0, 0, 0, 0, 0];
    for (let i = 0; i < E.length; i++) {
      const sy = Math.floor(ey1[i] / CELL) - Math.floor(ey0[i] / CELL);
      const sx = Math.floor(ex1[i] / CELL) - Math.floor(ex0[i] / CELL);
      const s = Math.max(sx, sy);
      h[s <= 1 ? 0 : s <= 2 ? 1 : s <= 4 ? 2 : s <= 8 ? 3 : s <= 16 ? 4 : s <= 32 ? 5 : 6]++;
    }
    dbg.spans = h; }
  canvas.__dbg = dbg;

  // ---- scene bitmap: every node as a dot over a world rect around the
  // current viewport; the live pass then overlays crisp circles only for
  // screen-visible (non-tiny) nodes. One drawImage per frame replaces up to
  // ~1M individual fillRects in the mid-zoom "dead zone". Rebuilt
  // (debounced) when the view leaves the covered rect or the 0.5..2 zoom
  // band; a stale bitmap is still drawn (transformed) in the meantime.
  // Dots are bucketed by color so a full-scene rebuild does ~20 state
  // changes instead of 3.5M.
  const colorBuckets = new Map();
  for (let u = 0; u < N.length; u++) {
    let b = colorBuckets.get(fills[u]);
    if (!b) colorBuckets.set(fills[u], b = []);
    b.push(u);
  }
  let bmp = null;              // {cv, x0, y0, ps} — ps = bitmap px per world px
  let bmpTimer = 0;
  const BMP_MARGIN = 0.6;      // covered rect = viewport grown by this on each side
  const bmpValid = () => {
    if (!bmp) return false;
    const vw = W() / zm.k, vh = H() / zm.k;
    const vx0 = -zm.x / zm.k, vy0 = -zm.y / zm.k;
    const r = zm.k / (bmp.ps / (window.devicePixelRatio || 1));
    return r >= 0.5 && r <= 2 &&
      vx0 >= bmp.x0 && vy0 >= bmp.y0 &&
      vx0 + vw <= bmp.x0 + bmp.cv.width / bmp.ps &&
      vy0 + vh <= bmp.y0 + bmp.cv.height / bmp.ps;
  };
  const rebuildBitmap = () => {
    const dpr = window.devicePixelRatio || 1;
    const vw = W() / zm.k, vh = H() / zm.k;
    const vx0 = -zm.x / zm.k, vy0 = -zm.y / zm.k;
    const x0 = vx0 - vw * BMP_MARGIN, y0 = vy0 - vh * BMP_MARGIN;
    const bw = vw * (1 + 2 * BMP_MARGIN), bh = vh * (1 + 2 * BMP_MARGIN);
    const ps = Math.min(zm.k * dpr, BMP_CAP / bw, BMP_CAP / bh);
    const c = document.createElement("canvas");
    c.width = Math.max(1, Math.round(bw * ps));
    c.height = Math.max(1, Math.round(bh * ps));
    const bg = c.getContext("2d");
    const cx0 = Math.floor(x0 / CELL), cy0 = Math.floor(y0 / CELL);
    const cx1 = Math.floor((x0 + bw) / CELL), cy1 = Math.floor((y0 + bh) / CELL);
    const full = (cx1 - cx0 + 1) * (cy1 - cy0 + 1) > 200000;
    // edges first (under the dots): one Path2D per width bucket, stroked
    // once each. 98% of edges here span >32 cells (the mega-SCC sits at the
    // root, longest-path layering stretches everything), so the roamer list
    // does the work — a flat typed-array bbox scan, ~15 ms, only on settle.
    const minWb = EDGE_MIN_PX / ps;
    eStampVal++;
    const bpaths = new Map();
    let eN = 0;
    const eadd = i => {
      if (ew[i] < minWb || eStamp[i] === eStampVal) return;
      eStamp[i] = eStampVal;
      if (ex1[i] < x0 || ex0[i] > x0 + bw || ey1[i] < y0 || ey0[i] > y0 + bh) return;
      eN++;
      const b = bucketOf(ew[i]);
      let p = bpaths.get(b);
      if (!p) bpaths.set(b, p = new Path2D());
      const a = N[E[i].s], t = N[E[i].t];
      const ax = (a.x - x0) * ps, ay = (a.y + a.rad + 2 - y0) * ps;
      const tx = (t.x - x0) * ps, ty = (t.y - t.rad - 4 - y0) * ps;
      p.moveTo(ax, ay);
      if (Math.abs(ty - ay) < 24) p.lineTo(tx, ty);
      else { const my = (ay + ty) / 2; p.bezierCurveTo(ax, my, tx, my, tx, ty); }
    };
    for (let p2 = 0; p2 < roamers.length; p2++) eadd(roamers[p2]);
    for (let cx = cx0; cx <= cx1; cx++) for (let cy = cy0; cy <= cy1; cy++) {
      const c2 = ci(cx, cy);
      for (let p2 = eOff[c2]; p2 < eOff[c2 + 1]; p2++) eadd(eIds[p2]);
    }
    bg.strokeStyle = C_EDGE;
    for (const [b, p] of bpaths) {
      bg.lineWidth = Math.max(EDGE_MIN_PX, 0.1 * Math.pow(2, b - 0.5) * ps);
      bg.stroke(p);
    }
    dbg.lastRebuild = { edges: eN, buckets: bpaths.size, ms: 0 };
    const dot = u => {
      const nd = N[u];
      const d = Math.max(1, nd.rad * 2 * ps);
      bg.fillRect((nd.x - x0) * ps - d / 2, (nd.y - y0) * ps - d / 2, d, d);
    };
    if (full) {
      for (const [color, ids] of colorBuckets) {
        bg.fillStyle = color;
        for (let i = 0; i < ids.length; i++) dot(ids[i]);
      }
    } else {
      for (let cx = cx0; cx <= cx1; cx++) for (let cy = cy0; cy <= cy1; cy++) {
        const c = ci(cx, cy);
        for (let p = nOff[c]; p < nOff[c + 1]; p++) {
          const u = nIds[p];
          const nd = N[u];
          if (nd.x < x0 || nd.x > x0 + bw || nd.y < y0 || nd.y > y0 + bh) continue;
          bg.fillStyle = fills[u];
          dot(u);
        }
      }
    }
    bmp = { cv: c, x0, y0, ps };
  };
  const scheduleBitmap = () => {
    clearTimeout(bmpTimer);
    bmpTimer = setTimeout(() => {
      if (!bmpValid()) {
        const t0 = performance.now();
        try { rebuildBitmap(); }
        catch (e) { dbg.lastRebuild = { err: String(e && e.stack || e) }; }
        if (dbg.lastRebuild) dbg.lastRebuild.ms = Math.round(performance.now() - t0);
        schedule();
      }
    }, 250);
  };

  // visible-edge label scan — debounced like the bitmap (it is a 3.4M-entry
  // flat scan, too expensive per frame)
  let visLabels = { count: 0, ids: [] };
  let labelTimer = 0;
  const scanLabels = () => {
    if (zm.k <= 0.35) { visLabels = { count: 0, ids: [] }; return; }
    const vx0 = -zm.x / zm.k, vy0 = -zm.y / zm.k;
    const vx1 = vx0 + W() / zm.k, vy1 = vy0 + H() / zm.k;
    const minW = EDGE_MIN_PX / zm.k;
    let count = 0;
    const ids = [];
    eStampVal++;
    const probe = i => {
      if (ew[i] < minW || eStamp[i] === eStampVal) return;
      eStamp[i] = eStampVal;
      if (ex1[i] < vx0 || ex0[i] > vx1 || ey1[i] < vy0 || ey0[i] > vy1) return;
      count++;
      if (ids.length <= EDGE_LABEL_MAX) ids.push(i);
    };
    for (let p = 0; p < roamers.length; p++) probe(roamers[p]);
    const cx0 = Math.floor(vx0 / CELL) - 1, cy0 = Math.floor(vy0 / CELL) - 1;
    const cx1 = Math.floor(vx1 / CELL) + 1, cy1 = Math.floor(vy1 / CELL) + 1;
    for (let cx = cx0; cx <= cx1; cx++) for (let cy = cy0; cy <= cy1; cy++) {
      const c = ci(cx, cy);
      for (let p = eOff[c]; p < eOff[c + 1]; p++) probe(eIds[p]);
    }
    visLabels = count <= EDGE_LABEL_MAX ? { count, ids } : { count, ids: [] };
  };
  const scheduleLabels = () => {
    clearTimeout(labelTimer);
    labelTimer = setTimeout(() => { scanLabels(); schedule(); }, 120);
  };

  // ---- view transform: screen = world * k + (x, y) ----
  let zm;
  const fit = () => {
    zm = { k: Math.min(1.4, (W() - 40) / Math.max(1, layout.width),
           (H() - 40) / Math.max(1, layout.height)), x: 0, y: 0 };
    zm.x = (W() - layout.width * zm.k) / 2;
    zm.y = (H() - layout.height * zm.k) / 2;
  };
  const zoomAt = (mx, my, dk) => {
    const k2 = Math.min(64, Math.max(zm.k * dk, 0.9 * Math.min((W() - 40) / layout.width,
      (H() - 40) / layout.height)));
    zm.x = mx - (mx - zm.x) * (k2 / zm.k); zm.y = my - (my - zm.y) * (k2 / zm.k); zm.k = k2;
  };
  fit();

  // ---- painting (rAF-throttled) ----
  let raf = 0;
  const schedule = () => { if (!raf) raf = requestAnimationFrame(() => { raf = 0; draw(); }); };

  const draw = () => {
    const dpr = window.devicePixelRatio || 1;
    const w = W(), h = H();
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, w, h);
    g.translate(zm.x, zm.y); g.scale(zm.k, zm.k);
    const vx0 = -zm.x / zm.k, vy0 = -zm.y / zm.k;
    const vx1 = vx0 + w / zm.k, vy1 = vy0 + h / zm.k;
    const cx0 = Math.floor(vx0 / CELL), cy0 = Math.floor(vy0 / CELL);
    const cx1 = Math.floor(vx1 / CELL), cy1 = Math.floor(vy1 / CELL);

    // ---- scene: one bitmap (edges + node dots) — built on settle, drawn
    // transformed while it is stale. 98% of edges span thousands of px, so
    // no per-frame index beats amortization.
    if (bmp) g.drawImage(bmp.cv, bmp.x0, bmp.y0, bmp.cv.width / bmp.ps, bmp.cv.height / bmp.ps);
    if (!bmpValid()) scheduleBitmap();

    // ---- edge ×mult labels at inspection zoom: the candidate scan walks
    // 3.4M flat bboxes (~15 ms), so it runs DEBOUNCED on settle, not per
    // frame — labels pop in ~120 ms after the view stops moving
    dbg.drawnEdges = visLabels.count;
    if (visLabels.ids.length) {
      g.font = `${10 / zm.k}px sans-serif`;
      g.textAlign = "left"; g.textBaseline = "middle";
      g.lineWidth = 3 / zm.k; g.strokeStyle = C_LABEL_BG; g.fillStyle = C_LABEL;
      for (const i of visLabels.ids) {
        const e = E[i];
        g.strokeText(`×${e.mult}`, e.mx + 4 / zm.k, e.my);
        g.fillText(`×${e.mult}`, e.mx + 4 / zm.k, e.my);
      }
    }

    // ---- nodes: live pass for screen-visible ones (bitmap has the rest) ----
    const sr = zm.k;
    const labeled = [];
    const drawNode = u => {
      const nd = N[u];
      const rpx = nd.rad * sr;
      if (rpx < TINY_R) {
        g.fillStyle = fills[u];
        const p = Math.max(1 / sr, nd.rad * 2);
        g.fillRect(nd.x - p / 2, nd.y - p / 2, p, p);
        return;
      }
      // mid tiers: under ~3 screen px a square reads as a dot; under ~6 px
      // the rim stroke is invisible — arc()+stroke() cost is what software
      // canvas can't pay for 20k nodes, so only pay it when it's visible
      if (rpx < 3) {
        g.fillStyle = fills[u];
        const p = nd.rad * 2;
        g.fillRect(nd.x - p / 2, nd.y - p / 2, p, p);
        return;
      }
      g.globalAlpha = 0.85;
      g.fillStyle = fills[u];
      g.beginPath(); g.arc(nd.x, nd.y, nd.rad, 0, 6.2831853); g.fill();
      g.globalAlpha = 1;
      if (rpx < 6) return;
      const isScc = nd.kind === "scc" || nd.kind === "scc-unique";
      g.strokeStyle = nd.roots ? C_ROOT_STROKE : C_NODE_STROKE;
      g.lineWidth = (nd.roots ? 1.6 : 0.8) / sr;
      if (isScc) g.setLineDash([3 / sr, 2 / sr]);
      g.stroke();
      if (isScc) g.setLineDash([]);
      if (rpx >= LABEL_R) labeled.push(u);
    };
    // live pass: nodes big enough on screen (bitmap has the rest as dots).
    // byRad prefix when few qualify (low zoom), grid cells when many (deep
    // zoom, small viewport)
    const need = TINY_R / sr;
    let lo = 0, hi = byRad.length;
    while (lo < hi) { const m = (lo + hi) >> 1; if (radOf[byRad[m]] >= need) lo = m + 1; else hi = m; }
    if (lo < 50000) {
      for (let i = 0; i < lo; i++) {
        const u = byRad[i], nd = N[u];
        if (nd.x + nd.rad < vx0 || nd.x - nd.rad > vx1 ||
            nd.y + nd.rad < vy0 || nd.y - nd.rad > vy1) continue;
        drawNode(u);
      }
    } else {
      for (let cx = cx0; cx <= cx1; cx++) {
        for (let cy = cy0; cy <= cy1; cy++) {
          const c = ci(cx, cy);
          for (let p = nOff[c]; p < nOff[c + 1]; p++) {
            const u = nIds[p];
            const nd = N[u];
            if (nd.rad * sr < TINY_R) continue;
            if (nd.x - nd.rad > vx1 || nd.x + nd.rad < vx0 ||
                nd.y - nd.rad > vy1 || nd.y + nd.rad < vy0) continue;
            drawNode(u);
          }
        }
      }
    }
    if (labeled.length) {
      g.font = `${11 / zm.k}px sans-serif`;
      g.textAlign = "center"; g.textBaseline = "top";
      g.fillStyle = C_LABEL;
      const halo = labeled.length <= 200;   // strokeText is the most
      if (halo) { g.lineWidth = 3 / zm.k; g.strokeStyle = C_LABEL_BG; }  // expensive call
      for (const u of labeled) {
        const nd = N[u];
        if (halo) g.strokeText(nd.short, nd.x, nd.y + nd.rad + 2 / zm.k);
        g.fillText(nd.short, nd.x, nd.y + nd.rad + 2 / zm.k);
      }
    }
    dbg.labels = labeled.length; dbg.k = zm.k;
    dbg.zm = { x: zm.x, y: zm.y, k: zm.k, w, h };
    dbg.bmpInfo = bmp && { x0: bmp.x0, y0: bmp.y0, ps: bmp.ps, cw: bmp.cv.width, ch: bmp.cv.height, valid: bmpValid() };
    // selection overlay: the selected node's own edges, batched once per
    // selection (Path2D is zoom-independent) — the bitmap keeps them in the
    // base color, these crisp colored copies ride on top
    if (selPaths) {
      for (const [key, p] of selPaths.paths) {
        const cls = (key / NB) | 0, b = key % NB;
        g.strokeStyle = cls === 0 ? C_EDGE_OUT : C_EDGE_IN;
        g.lineWidth = 0.1 * Math.pow(2, b - 0.5) * 1.5;
        g.stroke(p);
      }
      if (selPaths.degree <= 2000) {   // arrowheads for direction cues
        for (const [ei, out] of selPaths.list) {
          if (ew[ei] * zm.k < 3) continue;
          const e = E[ei], t = N[e.t];
          const y2 = t.y - t.rad - 4;
          const s = 2.2 * ew[ei], dir = y2 >= N[e.s].y ? 1 : -1;
          g.beginPath();
          g.moveTo(t.x - s * 0.45, y2 - dir * s);
          g.lineTo(t.x, y2);
          g.lineTo(t.x + s * 0.45, y2 - dir * s);
          g.closePath();
          g.fillStyle = out ? C_EDGE_OUT : C_EDGE_IN;
          g.fill();
        }
      }
    }
    if (shown >= 0) {
      const nd = N[shown];
      g.strokeStyle = C_SEL; g.lineWidth = 1.4 / zm.k;
      g.setLineDash([3 / zm.k, 3 / zm.k]);
      g.beginPath(); g.arc(nd.x, nd.y, nd.rad + 4 / zm.k, 0, 6.2831853); g.stroke();
      g.setLineDash([]);
    }
  };

  // canvas backing store follows CSS size × devicePixelRatio
  const resize = () => {
    const dpr = window.devicePixelRatio || 1;
    canvas.width = Math.max(1, Math.round(W() * dpr));
    canvas.height = Math.max(1, Math.round(H() * dpr));
    schedule();
  };
  const ro = new ResizeObserver(resize);
  ro.observe(canvas);
  resize();
  rebuildBitmap();   // scene bitmap for the initial fit view

  // ---- pan/zoom ----
  canvas.addEventListener("wheel", e => {
    e.preventDefault();
    if (e.ctrlKey || e.shiftKey) {
      const rc = canvas.getBoundingClientRect();
      zoomAt(e.clientX - rc.left, e.clientY - rc.top, e.deltaY < 0 ? 1.18 : 1 / 1.18);
    } else {
      zm.x -= e.deltaX; zm.y -= e.deltaY;
    }
    schedule(); scheduleBitmap(); scheduleLabels();
  }, { passive: false });
  let drag = null, moved = false;
  canvas.addEventListener("pointerdown", e => {
    drag = { x: e.clientX, y: e.clientY, zx: zm.x, zy: zm.y };
    moved = false;
  });
  const onMove = e => {
    if (!canvas.isConnected) { window.removeEventListener("pointermove", onMove); return; }
    if (!drag) return;
    const dx = e.clientX - drag.x, dy = e.clientY - drag.y;
    if (!moved && Math.abs(dx) + Math.abs(dy) > 5) { moved = true; canvas.classList.add("panning"); }
    if (moved) { zm.x = drag.zx + dx; zm.y = drag.zy + dy; schedule(); scheduleBitmap(); scheduleLabels(); }
  };
  const onUp = () => {
    if (!canvas.isConnected) { window.removeEventListener("pointerup", onUp); return; }
    drag = null; canvas.classList.remove("panning");
  };
  const onKey = e => {
    if (!canvas.isConnected) { window.removeEventListener("keydown", onKey); return; }
    const t = e.target;
    if (t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.isContentEditable)) return;
    if (e.key === "+" || e.key === "=") { zoomAt(W() / 2, H() / 2, 1.25); schedule(); scheduleBitmap(); scheduleLabels(); }
    else if (e.key === "-" || e.key === "_") { zoomAt(W() / 2, H() / 2, 0.8); schedule(); scheduleBitmap(); scheduleLabels(); }
    else if (e.key === "0") { fit(); schedule(); scheduleBitmap(); scheduleLabels(); }
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
  tBtn("−", "zoom out (−)", "", () => { zoomAt(W() / 2, H() / 2, 0.8); schedule(); scheduleBitmap(); scheduleLabels(); });
  tBtn("+", "zoom in (+)", "", () => { zoomAt(W() / 2, H() / 2, 1.25); schedule(); scheduleBitmap(); scheduleLabels(); });
  tBtn("fit", "fit to view (0)", "fit", () => { fit(); schedule(); scheduleBitmap(); scheduleLabels(); });

  // ---- hit-testing: grid lookup around the pointer, nearest node wins ----
  const hitTest = (sx, sy) => {
    const wx = (sx - zm.x) / zm.k, wy = (sy - zm.y) / zm.k;
    const cx = Math.floor(wx / CELL), cy = Math.floor(wy / CELL);
    let best = -1, bestD = Infinity;
    for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) {
      const c = ci(cx + dx, cy + dy);
      for (let p = nOff[c]; p < nOff[c + 1]; p++) {
        const u = nIds[p];
        const nd = N[u];
        const d = Math.hypot(nd.x - wx, nd.y - wy);
        if (d <= nd.rad && d < bestD) { best = u; bestD = d; }
      }
    }
    return best;
  };

  // the default panel depends only on the viewModel — build it ONCE (the
  // histogram used to re-join the mega-SCC's 274k children per reset)
  // selection overlay paths: Path2D is zoom-independent, so they are built
  // once per selection and just re-stroked per frame
  let selPaths = null;
  const buildSel = u => {
    const paths = new Map();           // key = cls(0 out, 1 in) * NB + bucket
    const list = [];
    const add = (ei, out) => {
      const e = E[ei], a = N[e.s], t = N[e.t];
      const key = (out ? 0 : 1) * NB + bucketOf(ew[ei]);
      let p = paths.get(key);
      if (!p) paths.set(key, p = new Path2D());
      const y1 = a.y + a.rad + 2, y2 = t.y - t.rad - 4;
      p.moveTo(a.x, y1);
      if (y2 - y1 < 2000) p.lineTo(t.x, y2);
      else { const my = (y1 + y2) / 2; p.bezierCurveTo(a.x, my, t.x, my, t.x, y2); }
      list.push([ei, out]);
    };
    for (const ei of adjSlice(outAdj, u)) add(ei, true);
    for (const ei of adjSlice(inAdj, u)) add(ei, false);
    return { paths, list, degree: list.length };
  };
  const defaultHtml = defaultSide(vm, ctx, outAdj);
  let refsCleanup = null;
  const showNode = u => {
    shown = u;
    selPaths = buildSel(u);
    if (refsCleanup) refsCleanup();
    side.innerHTML = nodeDetailHead(u, vm, ctx);
    refsCleanup = mountRefLists(side, u, vm, ctx, outAdj, inAdj, adjSlice);
    const ob = side.querySelector(".gopen");
    if (ob) ob.onclick = () => ctx.onOpenViz("flow", vm.dumpId, N[u].label);
    schedule();
  };
  const reset = () => {
    shown = -1;
    selPaths = null;
    if (refsCleanup) { refsCleanup(); refsCleanup = null; }
    side.innerHTML = defaultHtml;
    schedule();
  };
  side.innerHTML = defaultHtml;
  canvas.addEventListener("click", e => {
    if (moved) return;
    const rc = canvas.getBoundingClientRect();
    const u = hitTest(e.clientX - rc.left, e.clientY - rc.top);
    if (u >= 0) showNode(u); else reset();
  });
  canvas.addEventListener("dblclick", e => {
    const rc = canvas.getBoundingClientRect();
    const u = hitTest(e.clientX - rc.left, e.clientY - rc.top);
    if (u >= 0 && N[u].plain) ctx.onOpenViz("flow", vm.dumpId, N[u].label);
  });
}
