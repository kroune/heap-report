/* Shapes viz — Canvas 2D scene renderer and interaction lifecycle.
   Full-graph scale strategy: spatial culling, radius prefixes, width LOD,
   overview bitmap, CSR adjacency and rAF-throttled painting. No data is
   fetched or capped here. mountShapesCanvas returns an idempotent cleanup. */

import { adjSlice, buildScene, edgeWidthBucket, fitTransform, hitTest, zoomTransform, EDGE_BUCKETS } from "./scene.js";
import { defaultSide, mountRefLists, nodeDetailHead } from "./details.js";

const TINY_R = 0.7;
const EDGE_MIN_PX = 0.35;
const BMP_EDGE_MIN_PX = 1.25;
const LABEL_R = 14;
const EDGE_LABEL_MAX = 120;
const BMP_CAP = 4096;
const BMP_MARGIN = 0.6;

const C_EDGE = "rgba(74,85,104,0.5)";
const C_EDGE_OUT = "rgba(121,184,255,0.95)";
const C_EDGE_IN = "rgba(240,136,62,0.95)";
const C_NODE_STROKE = "#0d1117";
const C_ROOT_STROKE = "#e8edf5";
const C_SEL = "#e8edf5";
const C_LABEL = "#9aa4b2";
const C_LABEL_BG = "#0a0d12";
const C_INTERNAL = "rgba(154,164,178,0.72)";
const C_SCC_FILL = "#101721";
const C_MEMBER_FILL = "#131a24";
const C_MEMBER_TEXT = "#e8edf5";

export function mountShapesCanvas(canvas, tools, side, vm, ctx) {
  let layout = vm.layout;
  let N = layout.nodes, E = layout.edges;
  let scene = buildScene(layout, N.map(nd => ctx.catColor(nd.cat)));
  let disposed = false;
  let shown = -1, selPaths = null, refsCleanup = null;
  let defaultHtml = null;
  let bmp = null, bmpTimer = 0, labelTimer = 0, raf = 0;
  let visLabels = { count: 0, ids: [] };
  let drag = null, moved = false;
  let ro = null;
  const g = canvas.getContext("2d");
  const W = () => canvas.clientWidth || 760;
  const H = () => canvas.clientHeight || 620;
  const dbg = { roamers: scene.roamers.length, drawnEdges: 0, labels: 0, k: 0,
    zm: null, bmpInfo: null, spans: scene.spans };
  canvas.__dbg = dbg;

  function cleanup() {
    if (disposed) return;
    disposed = true;
    if (raf) cancelAnimationFrame(raf);
    raf = 0;
    clearTimeout(bmpTimer);
    clearTimeout(labelTimer);
    bmpTimer = 0;
    labelTimer = 0;
    if (ro) ro.disconnect();
    ro = null;
    window.removeEventListener("pointermove", onMove);
    window.removeEventListener("pointerup", onUp);
    window.removeEventListener("keydown", onKey);
    canvas.removeEventListener("wheel", onWheel);
    canvas.removeEventListener("pointerdown", onPointerDown);
    canvas.removeEventListener("click", onClick);
    canvas.removeEventListener("dblclick", onDblClick);
    if (refsCleanup) refsCleanup();
    refsCleanup = null;
    tools.textContent = "";
    side.textContent = "";
    canvas.classList.remove("panning");
    canvas.__dbg = null;
    canvas.width = 1;
    canvas.height = 1;
    if (bmp) { bmp.cv.width = 1; bmp.cv.height = 1; }
    bmp = null;
    visLabels = null;
    selPaths = null;
    defaultHtml = null;
    drag = null;
    scene = null;
    layout = null;
    N = null;
    E = null;
    vm = null;
  }

  /* Also self-dispose if a host outside viz/common removes the canvas. */
  function active() {
    if (disposed) return false;
    if (!canvas.isConnected) { cleanup(); return false; }
    return true;
  }

  let zm = fitTransform(W(), H(), layout.width, layout.height);
  const fit = () => { zm = fitTransform(W(), H(), layout.width, layout.height); };
  const zoomAt = (mx, my, dk) => {
    zm = zoomTransform(zm, mx, my, dk, W(), H(), layout.width, layout.height);
  };

  const bmpValid = () => {
    if (!bmp || !scene) return false;
    const vw = W() / zm.k, vh = H() / zm.k;
    const vx0 = -zm.x / zm.k, vy0 = -zm.y / zm.k;
    const ratio = zm.k / (bmp.ps / (window.devicePixelRatio || 1));
    return ratio >= 0.5 && ratio <= 2 &&
      vx0 >= bmp.x0 && vy0 >= bmp.y0 &&
      vx0 + vw <= bmp.x0 + bmp.cv.width / bmp.ps &&
      vy0 + vh <= bmp.y0 + bmp.cv.height / bmp.ps;
  };

  function schedule() {
    if (!active() || raf) return;
    raf = requestAnimationFrame(() => {
      raf = 0;
      if (active()) draw();
    });
  }

  function rebuildBitmap() {
    if (!active()) return;
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
    const range = scene.grid.range(x0, y0, x0 + bw, y0 + bh);
    const full = !!range && (range.cx1 - range.cx0 + 1) * (range.cy1 - range.cy0 + 1) > 200000;

    // Edges first, batched into one Path2D per width bucket.
    const minWb = Math.max(EDGE_MIN_PX, BMP_EDGE_MIN_PX) / ps;
    const stamp = scene.nextEdgeStamp();
    const bpaths = new Map();
    let eN = 0;
    const eadd = i => {
      if (scene.ew[i] < minWb || scene.eStamp[i] === stamp) return;
      scene.eStamp[i] = stamp;
      if (scene.ex1[i] < x0 || scene.ex0[i] > x0 + bw ||
          scene.ey1[i] < y0 || scene.ey0[i] > y0 + bh) return;
      eN++;
      const b = edgeWidthBucket(scene.ew[i]);
      let p = bpaths.get(b);
      if (!p) bpaths.set(b, p = new Path2D());
      const a = N[E[i].s], t = N[E[i].t];
      const ax = (a.x - x0) * ps, ay = (a.y + a.hh + 2 - y0) * ps;
      const tx = (t.x - x0) * ps, ty = (t.y - t.hh - 4 - y0) * ps;
      p.moveTo(ax, ay);
      if (Math.abs(ty - ay) < 24) p.lineTo(tx, ty);
      else { const my = (ay + ty) / 2; p.bezierCurveTo(ax, my, tx, my, tx, ty); }
    };
    for (let p = 0; p < scene.roamers.length; p++) eadd(scene.roamers[p]);
    if (range) {
      for (let cx = range.cx0; cx <= range.cx1; cx++) {
        for (let cy = range.cy0; cy <= range.cy1; cy++) {
          const cell = scene.grid.index(cx, cy);
          for (let p = scene.eOff[cell]; p < scene.eOff[cell + 1]; p++) eadd(scene.eIds[p]);
        }
      }
    }
    bg.strokeStyle = C_EDGE;
    for (const [b, p] of bpaths) {
      bg.lineWidth = Math.max(EDGE_MIN_PX, 0.1 * Math.pow(2, b - 0.5) * ps);
      bg.stroke(p);
    }
    dbg.lastRebuild = { edges: eN, buckets: bpaths.size, ms: 0 };

    const dot = u => {
      const nd = N[u];
      const w = Math.max(1, (nd.scc ? nd.hw : nd.rad) * 2 * ps);
      const h = Math.max(1, (nd.scc ? nd.hh : nd.rad) * 2 * ps);
      bg.fillRect((nd.x - x0) * ps - w / 2, (nd.y - y0) * ps - h / 2, w, h);
    };
    if (full) {
      for (const [color, ids] of scene.colorBuckets) {
        bg.fillStyle = color;
        for (let i = 0; i < ids.length; i++) dot(ids[i]);
      }
    } else if (range) {
      for (let cx = range.cx0; cx <= range.cx1; cx++) {
        for (let cy = range.cy0; cy <= range.cy1; cy++) {
          const cell = scene.grid.index(cx, cy);
          for (let p = scene.nOff[cell]; p < scene.nOff[cell + 1]; p++) {
            const u = scene.nIds[p], nd = N[u];
            if (nd.x < x0 || nd.x > x0 + bw || nd.y < y0 || nd.y > y0 + bh) continue;
            bg.fillStyle = scene.fills[u];
            dot(u);
          }
        }
      }
    }
    bmp = { cv: c, x0, y0, ps };
  }

  function scheduleBitmap() {
    if (!active()) return;
    clearTimeout(bmpTimer);
    bmpTimer = setTimeout(() => {
      bmpTimer = 0;
      if (!active() || bmpValid()) return;
      const t0 = performance.now();
      try { rebuildBitmap(); }
      catch (e) { dbg.lastRebuild = { err: String(e && e.stack || e) }; }
      if (dbg.lastRebuild) dbg.lastRebuild.ms = Math.round(performance.now() - t0);
      schedule();
    }, 250);
  }

  function scanLabels() {
    if (!active()) return;
    if (zm.k <= 0.35) { visLabels = { count: 0, ids: [] }; return; }
    const vx0 = -zm.x / zm.k, vy0 = -zm.y / zm.k;
    const vx1 = vx0 + W() / zm.k, vy1 = vy0 + H() / zm.k;
    const minW = EDGE_MIN_PX / zm.k;
    let count = 0;
    const ids = [];
    const stamp = scene.nextEdgeStamp();
    const probe = i => {
      if (scene.ew[i] < minW || scene.eStamp[i] === stamp) return;
      scene.eStamp[i] = stamp;
      if (scene.ex1[i] < vx0 || scene.ex0[i] > vx1 ||
          scene.ey1[i] < vy0 || scene.ey0[i] > vy1) return;
      count++;
      if (ids.length <= EDGE_LABEL_MAX) ids.push(i);
    };
    for (let p = 0; p < scene.roamers.length; p++) probe(scene.roamers[p]);
    const range = scene.grid.range(vx0, vy0, vx1, vy1, 1);
    if (range) {
      for (let cx = range.cx0; cx <= range.cx1; cx++) {
        for (let cy = range.cy0; cy <= range.cy1; cy++) {
          const cell = scene.grid.index(cx, cy);
          for (let p = scene.eOff[cell]; p < scene.eOff[cell + 1]; p++) probe(scene.eIds[p]);
        }
      }
    }
    visLabels = count <= EDGE_LABEL_MAX ? { count, ids } : { count, ids: [] };
  }

  function scheduleLabels() {
    if (!active()) return;
    clearTimeout(labelTimer);
    labelTimer = setTimeout(() => {
      labelTimer = 0;
      if (!active()) return;
      scanLabels();
      schedule();
    }, 120);
  }

  function buildSelection(u) {
    const paths = new Map();
    const list = [];
    const add = (ei, out) => {
      const e = E[ei], a = N[e.s], t = N[e.t];
      const key = (out ? 0 : 1) * EDGE_BUCKETS + edgeWidthBucket(scene.ew[ei]);
      let p = paths.get(key);
      if (!p) paths.set(key, p = new Path2D());
      const y1 = a.y + a.hh + 2, y2 = t.y - t.hh - 4;
      p.moveTo(a.x, y1);
      if (y2 - y1 < 2000) p.lineTo(t.x, y2);
      else { const my = (y1 + y2) / 2; p.bezierCurveTo(a.x, my, t.x, my, t.x, y2); }
      list.push([ei, out]);
    };
    for (const ei of adjSlice(scene.outAdj, u)) add(ei, true);
    for (const ei of adjSlice(scene.inAdj, u)) add(ei, false);
    return { paths, list, degree: list.length };
  }

  /* Paint one complete SCC in local coordinates. Internal geometry is never
     capped: LOD only decides when the boxes/edges are large enough to see. */
  function drawSccNode(u) {
    const nd = N[u], sc = nd.scc;
    g.save();
    g.translate(nd.x, nd.y);
    g.beginPath();
    g.rect(-nd.hw, -nd.hh, nd.hw * 2, nd.hh * 2);
    g.fillStyle = C_SCC_FILL;
    g.fill();
    g.strokeStyle = nd.roots ? C_ROOT_STROKE : scene.fills[u];
    g.lineWidth = (nd.roots ? 1.6 : 1.1) / zm.k;
    g.setLineDash([4 / zm.k, 3 / zm.k]);
    g.stroke();
    g.setLineDash([]);

    let header = false;
    if (11 * zm.k >= 7) {
      header = true;
      g.font = "11px sans-serif";
      g.textAlign = "left";
      g.textBaseline = "middle";
      g.fillStyle = C_MEMBER_TEXT;
      g.fillText(nd.short, -nd.hw + 8, -nd.hh + sc.headerHeight / 2, sc.width - 16);
    }

    const showGraph = nd.hh * 2 * zm.k >= 40 && sc.members[0].h * zm.k >= 3;
    if (showGraph) {
      g.save();
      g.beginPath();
      g.rect(-nd.hw + 1, -nd.hh + 1, sc.width - 2, sc.height - 2);
      g.clip();
      g.strokeStyle = C_INTERNAL;
      g.fillStyle = C_INTERNAL;
      g.lineWidth = 0.8 / zm.k;
      const arrowSize = Math.max(2.5, Math.min(6, 3 / zm.k));
      for (const e of sc.edges) {
        g.beginPath();
        g.moveTo(e.x1, e.y1);
        g.bezierCurveTo(e.c1x, e.c1y, e.c2x, e.c2y, e.x2, e.y2);
        g.stroke();
        const angle = Math.atan2(e.y2 - e.c2y, e.x2 - e.c2x);
        g.beginPath();
        g.moveTo(e.x2, e.y2);
        g.lineTo(e.x2 - arrowSize * Math.cos(angle - Math.PI / 6),
          e.y2 - arrowSize * Math.sin(angle - Math.PI / 6));
        g.lineTo(e.x2 - arrowSize * Math.cos(angle + Math.PI / 6),
          e.y2 - arrowSize * Math.sin(angle + Math.PI / 6));
        g.closePath();
        g.fill();
      }
      g.lineWidth = 0.8 / zm.k;
      for (const m of sc.members) {
        g.fillStyle = C_MEMBER_FILL;
        g.strokeStyle = ctx.catColor(m.cat);
        g.beginPath();
        g.rect(m.x - m.w / 2, m.y - m.h / 2, m.w, m.h);
        g.fill();
        g.stroke();
      }
      if (9 * zm.k >= 7) {
        g.font = "9px sans-serif";
        g.textAlign = "center";
        g.textBaseline = "middle";
        g.fillStyle = C_MEMBER_TEXT;
        for (const m of sc.members) g.fillText(m.short, m.x, m.y + 0.5, m.w - 6);
        g.lineWidth = 2.5;
        g.strokeStyle = C_LABEL_BG;
        for (const e of sc.edges) if (e.mult > 1) {
          g.strokeText(`×${e.mult}`, e.mx, e.my);
          g.fillText(`×${e.mult}`, e.mx, e.my);
        }
      }
      g.restore();
    }
    g.restore();
    return header;
  }

  function draw() {
    if (!active()) return;
    const dpr = window.devicePixelRatio || 1;
    const w = W(), h = H();
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, w, h);
    g.translate(zm.x, zm.y);
    g.scale(zm.k, zm.k);
    const vx0 = -zm.x / zm.k, vy0 = -zm.y / zm.k;
    const vx1 = vx0 + w / zm.k, vy1 = vy0 + h / zm.k;
    const range = scene.grid.range(vx0, vy0, vx1, vy1);

    if (bmp) g.drawImage(bmp.cv, bmp.x0, bmp.y0, bmp.cv.width / bmp.ps, bmp.cv.height / bmp.ps);
    if (!bmpValid()) scheduleBitmap();

    dbg.drawnEdges = visLabels.count;
    if (visLabels.ids.length) {
      g.font = `${10 / zm.k}px sans-serif`;
      g.textAlign = "left";
      g.textBaseline = "middle";
      g.lineWidth = 3 / zm.k;
      g.strokeStyle = C_LABEL_BG;
      g.fillStyle = C_LABEL;
      for (const i of visLabels.ids) {
        const e = E[i];
        g.strokeText(`×${e.mult}`, e.mx + 4 / zm.k, e.my);
        g.fillText(`×${e.mult}`, e.mx + 4 / zm.k, e.my);
      }
    }

    const labeled = [];
    const drawNode = u => {
      const nd = N[u];
      if (nd.scc) return;   // bbox-indexed containers are painted below
      const rpx = nd.rad * zm.k;
      if (rpx < TINY_R) {
        g.fillStyle = scene.fills[u];
        const p = Math.max(1 / zm.k, nd.rad * 2);
        g.fillRect(nd.x - p / 2, nd.y - p / 2, p, p);
        return;
      }
      if (rpx < 3) {
        g.fillStyle = scene.fills[u];
        const p = nd.rad * 2;
        g.fillRect(nd.x - p / 2, nd.y - p / 2, p, p);
        return;
      }
      g.globalAlpha = 0.85;
      g.fillStyle = scene.fills[u];
      g.beginPath();
      g.arc(nd.x, nd.y, nd.rad, 0, 6.2831853);
      g.fill();
      g.globalAlpha = 1;
      if (rpx < 6) return;
      const isScc = nd.kind === "scc" || nd.kind === "scc-unique";
      g.strokeStyle = nd.roots ? C_ROOT_STROKE : C_NODE_STROKE;
      g.lineWidth = (nd.roots ? 1.6 : 0.8) / zm.k;
      if (isScc) g.setLineDash([3 / zm.k, 2 / zm.k]);
      g.stroke();
      if (isScc) g.setLineDash([]);
      if (rpx >= LABEL_R) labeled.push(u);
    };

    const need = TINY_R / zm.k;
    let lo = 0, hi = scene.byRad.length;
    while (lo < hi) {
      const m = (lo + hi) >> 1;
      if (scene.radOf[scene.byRad[m]] >= need) lo = m + 1;
      else hi = m;
    }
    if (lo < 50000) {
      for (let i = 0; i < lo; i++) {
        const u = scene.byRad[i], nd = N[u];
        if (nd.x + nd.rad < vx0 || nd.x - nd.rad > vx1 ||
            nd.y + nd.rad < vy0 || nd.y - nd.rad > vy1) continue;
        drawNode(u);
      }
    } else if (range) {
      for (let cx = range.cx0; cx <= range.cx1; cx++) {
        for (let cy = range.cy0; cy <= range.cy1; cy++) {
          const cell = scene.grid.index(cx, cy);
          for (let p = scene.nOff[cell]; p < scene.nOff[cell + 1]; p++) {
            const u = scene.nIds[p], nd = N[u];
            if (nd.rad * zm.k < TINY_R) continue;
            if (nd.x - nd.rad > vx1 || nd.x + nd.rad < vx0 ||
                nd.y - nd.rad > vy1 || nd.y + nd.rad < vy0) continue;
            drawNode(u);
          }
        }
      }
    }

    // SCC boxes can cover cells that do not contain their center; their
    // separate bbox index both finds and de-duplicates them for this frame.
    if (range) {
      const stamp = scene.nextNodeStamp();
      for (let cx = range.cx0; cx <= range.cx1; cx++) {
        for (let cy = range.cy0; cy <= range.cy1; cy++) {
          const cell = scene.grid.index(cx, cy);
          for (let p = scene.boxOff[cell]; p < scene.boxOff[cell + 1]; p++) {
            const u = scene.boxIds[p];
            if (scene.nStamp[u] === stamp) continue;
            scene.nStamp[u] = stamp;
            if (!drawSccNode(u) && N[u].rad * zm.k >= LABEL_R) labeled.push(u);
          }
        }
      }
    }

    if (labeled.length) {
      g.font = `${11 / zm.k}px sans-serif`;
      g.textAlign = "center";
      g.textBaseline = "top";
      g.fillStyle = C_LABEL;
      const halo = labeled.length <= 200;
      if (halo) { g.lineWidth = 3 / zm.k; g.strokeStyle = C_LABEL_BG; }
      for (const u of labeled) {
        const nd = N[u];
        if (halo) g.strokeText(nd.short, nd.x, nd.y + nd.hh + 2 / zm.k);
        g.fillText(nd.short, nd.x, nd.y + nd.hh + 2 / zm.k);
      }
    }
    dbg.labels = labeled.length;
    dbg.k = zm.k;
    dbg.zm = { x: zm.x, y: zm.y, k: zm.k, w, h };
    dbg.bmpInfo = bmp && { x0: bmp.x0, y0: bmp.y0, ps: bmp.ps,
      cw: bmp.cv.width, ch: bmp.cv.height, valid: bmpValid() };

    if (selPaths) {
      for (const [key, p] of selPaths.paths) {
        const cls = (key / EDGE_BUCKETS) | 0, b = key % EDGE_BUCKETS;
        g.strokeStyle = cls === 0 ? C_EDGE_OUT : C_EDGE_IN;
        g.lineWidth = 0.1 * Math.pow(2, b - 0.5) * 1.5;
        g.stroke(p);
      }
      if (selPaths.degree <= 2000) {
        for (const [ei, out] of selPaths.list) {
          if (scene.ew[ei] * zm.k < 3) continue;
          const e = E[ei], t = N[e.t];
          const y2 = t.y - t.hh - 4;
          const s = 2.2 * scene.ew[ei], dir = y2 >= N[e.s].y ? 1 : -1;
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
      g.strokeStyle = C_SEL;
      g.lineWidth = 1.4 / zm.k;
      g.setLineDash([3 / zm.k, 3 / zm.k]);
      g.beginPath();
      if (nd.scc) g.rect(nd.x - nd.hw - 4 / zm.k, nd.y - nd.hh - 4 / zm.k,
        nd.hw * 2 + 8 / zm.k, nd.hh * 2 + 8 / zm.k);
      else g.arc(nd.x, nd.y, nd.rad + 4 / zm.k, 0, 6.2831853);
      g.stroke();
      g.setLineDash([]);
    }
  }

  function resize() {
    if (!active()) return;
    const dpr = window.devicePixelRatio || 1;
    canvas.width = Math.max(1, Math.round(W() * dpr));
    canvas.height = Math.max(1, Math.round(H() * dpr));
    schedule();
  }

  function showNode(u) {
    if (!active()) return;
    shown = u;
    selPaths = buildSelection(u);
    if (refsCleanup) refsCleanup();
    side.innerHTML = nodeDetailHead(u, vm, ctx);
    refsCleanup = mountRefLists(side, u, vm, ctx, scene.outAdj, scene.inAdj, adjSlice);
    const open = side.querySelector(".gopen");
    if (open) open.onclick = () => ctx.onOpenViz("flow", vm.dumpId, N[u].label);
    schedule();
  }

  function reset() {
    if (!active()) return;
    shown = -1;
    selPaths = null;
    if (refsCleanup) refsCleanup();
    refsCleanup = null;
    side.innerHTML = defaultHtml;
    schedule();
  }

  function onWheel(e) {
    if (!active()) return;
    e.preventDefault();
    if (e.ctrlKey || e.shiftKey) {
      const rc = canvas.getBoundingClientRect();
      zoomAt(e.clientX - rc.left, e.clientY - rc.top, e.deltaY < 0 ? 1.18 : 1 / 1.18);
    } else {
      zm.x -= e.deltaX;
      zm.y -= e.deltaY;
    }
    schedule();
    scheduleBitmap();
    scheduleLabels();
  }

  function onPointerDown(e) {
    if (!active()) return;
    drag = { x: e.clientX, y: e.clientY, zx: zm.x, zy: zm.y };
    moved = false;
  }

  function onMove(e) {
    if (!active() || !drag) return;
    const dx = e.clientX - drag.x, dy = e.clientY - drag.y;
    if (!moved && Math.abs(dx) + Math.abs(dy) > 5) {
      moved = true;
      canvas.classList.add("panning");
    }
    if (moved) {
      zm.x = drag.zx + dx;
      zm.y = drag.zy + dy;
      schedule();
      scheduleBitmap();
      scheduleLabels();
    }
  }

  function onUp() {
    if (!active()) return;
    drag = null;
    canvas.classList.remove("panning");
  }

  function onKey(e) {
    if (!active()) return;
    const t = e.target;
    if (t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.isContentEditable)) return;
    if (e.key === "+" || e.key === "=") zoomAt(W() / 2, H() / 2, 1.25);
    else if (e.key === "-" || e.key === "_") zoomAt(W() / 2, H() / 2, 0.8);
    else if (e.key === "0") fit();
    else return;
    schedule();
    scheduleBitmap();
    scheduleLabels();
  }

  function onClick(e) {
    if (!active() || moved) return;
    const rc = canvas.getBoundingClientRect();
    const wx = (e.clientX - rc.left - zm.x) / zm.k;
    const wy = (e.clientY - rc.top - zm.y) / zm.k;
    const u = hitTest(scene, wx, wy);
    if (u >= 0) showNode(u);
    else reset();
  }

  function onDblClick(e) {
    if (!active()) return;
    const rc = canvas.getBoundingClientRect();
    const wx = (e.clientX - rc.left - zm.x) / zm.k;
    const wy = (e.clientY - rc.top - zm.y) / zm.k;
    const u = hitTest(scene, wx, wy);
    if (u >= 0 && N[u].plain) ctx.onOpenViz("flow", vm.dumpId, N[u].label);
  }

  function toolButton(txt, title, cls, fn) {
    const b = document.createElement("button");
    b.textContent = txt;
    b.title = title;
    if (cls) b.className = cls;
    b.onclick = () => {
      if (!active()) return;
      fn();
      schedule();
      scheduleBitmap();
      scheduleLabels();
    };
    tools.appendChild(b);
  }

  // Build the static panel once; selection only replaces it on demand.
  defaultHtml = defaultSide(vm, ctx, scene.outAdj);
  side.innerHTML = defaultHtml;
  try {
    canvas.addEventListener("wheel", onWheel, { passive: false });
    canvas.addEventListener("pointerdown", onPointerDown);
    canvas.addEventListener("click", onClick);
    canvas.addEventListener("dblclick", onDblClick);
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
    window.addEventListener("keydown", onKey);
    toolButton("−", "zoom out (−)", "", () => zoomAt(W() / 2, H() / 2, 0.8));
    toolButton("+", "zoom in (+)", "", () => zoomAt(W() / 2, H() / 2, 1.25));
    toolButton("fit", "fit to view (0)", "fit", fit);

    ro = new ResizeObserver(resize);
    ro.observe(canvas);
    resize();
    rebuildBitmap();
  } catch (e) {
    cleanup();
    throw e;
  }
  return cleanup;
}
