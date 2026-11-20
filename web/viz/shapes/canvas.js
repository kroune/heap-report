/* Shapes viz — interaction lifecycle plus a two-layer renderer. A WebGL2
   canvas underneath (gl.js) draws every edge and plain node each frame, so
   panning and zooming never wait for a debounced bitmap, edges stay visible
   at every scale and nodes stay circles. The supplied canvas on top is the
   Canvas-2D overlay: SCC containers with their full member graphs, labels,
   multiplicity texts and the selection ring/arrowheads. No data is fetched
   or capped here. mountShapesCanvas returns an idempotent cleanup. */

import { adjSlice, buildScene, fitTransform, hitTest, resizeTransform, zoomTransform } from "./scene.js";
import { createShapesGl } from "./gl.js";
import { defaultSide, mountRefLists, nodeDetailHead } from "./details.js";

const LABEL_R = 14;
const LABEL_MAX = 400;
const EDGE_LABEL_MAX = 120;
const EDGE_MIN_PX = 0.35;      // fallback renderer only
const FALLBACK_EDGE_MAX = 30000;
const CULL_CELL_MAX = 30000;   // wider viewports use the GL wide pass directly
const CULL_EDGE_MAX = 60000;   // larger visible sets stay on the wide pass

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

export function mountShapesCanvas(canvas, tools, side, vm, ctx, hooks = {}) {
  let layout = vm.layout;
  let N = layout.nodes, E = layout.edges;
  let fills = N.map(nd => ctx.catColor(nd.cat));
  let scene = buildScene(layout, fills);
  let disposed = false;
  let shown = -1, selList = null, refsCleanup = null;
  let defaultHtml = null;
  let labelTimer = 0, raf = 0;
  let visLabels = { count: 0, ids: [] };
  let drag = null, moved = false;
  let ro = null;
  const sccIds = [];
  for (let u = 0; u < N.length; u++) if (N[u].scc) sccIds.push(u);
  /* Both layers are absolutely positioned inside the (relative) wrap. An
     in-flow canvas is a feedback loop: resize() writes clientSize×dpr into
     the canvas attributes, the attribute size is the canvas's intrinsic
     layout size, and an unbounded flex host grows the wrap to match — the
     pane inflated to tens of thousands of px and the "center" zoom anchor
     landed far below the visible viewport. */
  const hostCanvasStyle = canvas.style.cssText;
  canvas.style.position = "absolute";
  canvas.style.inset = "0";
  canvas.style.width = "100%";
  canvas.style.height = "100%";
  let glCanvas = document.createElement("canvas");
  glCanvas.className = "shapes-canvas-gl";
  glCanvas.style.cssText = "position:absolute;left:0;top:0;width:100%;height:100%;pointer-events:none;";
  canvas.parentElement.insertBefore(glCanvas, canvas);
  let glr = null;
  try {
    glr = createShapesGl(glCanvas);
    if (glr) glr.upload(N, E, scene.ew, fills);
  } catch { glr = null; }
  const g = canvas.getContext("2d");
  const W = () => canvas.clientWidth || 760;
  const H = () => canvas.clientHeight || 620;
  const dbg = { gl: !!glr, drawnEdges: 0, labels: 0, k: 0, zm: null, frameMs: 0 };
  canvas.__dbg = dbg;

  function cleanup() {
    if (disposed) return;
    disposed = true;
    if (raf) cancelAnimationFrame(raf);
    raf = 0;
    clearTimeout(labelTimer);
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
    if (tools) tools.textContent = "";
    if (side) side.textContent = "";
    canvas.classList.remove("panning");
    canvas.__dbg = null;
    canvas.width = 1;
    canvas.height = 1;
    canvas.style.cssText = hostCanvasStyle;
    if (glr) glr.dispose();
    glr = null;
    if (glCanvas.parentElement) glCanvas.parentElement.removeChild(glCanvas);
    glCanvas = null;
    visLabels = null;
    selList = null;
    defaultHtml = null;
    drag = null;
    scene = null;
    layout = null;
    N = null;
    E = null;
    fills = null;
    vm = null;
  }

  /* Also self-dispose if a host outside viz/common removes the canvas. */
  function active() {
    if (disposed) return false;
    if (!canvas.isConnected) { cleanup(); return false; }
    return true;
  }

  let customView = false;
  let viewW = W(), viewH = H();
  let zm = fitTransform(viewW, viewH, layout.width, layout.height);
  const fit = () => {
    customView = false;
    zm = fitTransform(W(), H(), layout.width, layout.height);
  };
  const zoomAt = (mx, my, dk) => {
    customView = true;
    zm = zoomTransform(zm, mx, my, dk, W(), H(), layout.width, layout.height);
  };

  function schedule() {
    if (!active() || raf) return;
    raf = requestAnimationFrame(() => {
      raf = 0;
      if (active()) draw();
    });
  }

  /* Viewport-culled edge ids for the GL zoom pass; null selects the wide
     line pass. Recomputed only when the viewport actually changes. */
  let lastCull = null;
  function updateCull(vx0, vy0, vx1, vy1, range) {
    if (!glr) return;
    const key = `${zm.x}|${zm.y}|${zm.k}|${viewW}|${viewH}`;
    if (lastCull === key) return;
    lastCull = key;
    if (!range) { glr.setCull([]); return; }
    const rangeCells = (range.cx1 - range.cx0 + 1) * (range.cy1 - range.cy0 + 1);
    if (rangeCells > CULL_CELL_MAX) { glr.setCull(null); return; }
    const ids = [];
    const stamp = scene.nextEdgeStamp();
    const take = i => {
      if (scene.eStamp[i] === stamp) return;
      scene.eStamp[i] = stamp;
      if (scene.ex1[i] < vx0 || scene.ex0[i] > vx1 ||
          scene.ey1[i] < vy0 || scene.ey0[i] > vy1) return;
      ids.push(i);
    };
    for (let p = 0; p < scene.roamers.length; p++) {
      take(scene.roamers[p]);
      if (ids.length > CULL_EDGE_MAX) { glr.setCull(null); return; }
    }
    for (let cx = range.cx0; cx <= range.cx1; cx++) {
      for (let cy = range.cy0; cy <= range.cy1; cy++) {
        const cell = scene.grid.index(cx, cy);
        for (let p = scene.eOff[cell]; p < scene.eOff[cell + 1]; p++) take(scene.eIds[p]);
        if (ids.length > CULL_EDGE_MAX) { glr.setCull(null); return; }
      }
    }
    glr.setCull(ids);
  }

  /* Multiplicity texts appear once few enough edges are visible to read them.
     Early exit: past the cap only the fact that there are too many matters. */
  function scanLabels() {
    if (!active()) return;
    if (zm.k <= 0.35) { visLabels = { count: 0, ids: [] }; return; }
    const vx0 = -zm.x / zm.k, vy0 = -zm.y / zm.k;
    const vx1 = vx0 + W() / zm.k, vy1 = vy0 + H() / zm.k;
    let count = 0;
    const ids = [];
    const stamp = scene.nextEdgeStamp();
    const probe = i => {
      if (count > EDGE_LABEL_MAX || scene.eStamp[i] === stamp) return;
      scene.eStamp[i] = stamp;
      if (scene.ex1[i] < vx0 || scene.ex0[i] > vx1 ||
          scene.ey1[i] < vy0 || scene.ey0[i] > vy1) return;
      count++;
      if (ids.length <= EDGE_LABEL_MAX) ids.push(i);
    };
    for (let p = 0; p < scene.roamers.length && count <= EDGE_LABEL_MAX; p++) probe(scene.roamers[p]);
    const range = scene.grid.range(vx0, vy0, vx1, vy1, 1);
    if (range) {
      for (let cx = range.cx0; cx <= range.cx1 && count <= EDGE_LABEL_MAX; cx++) {
        for (let cy = range.cy0; cy <= range.cy1 && count <= EDGE_LABEL_MAX; cy++) {
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
    const dashed = nd.hw * 2 * zm.k >= 10;   // dash segments are invisible below this
    if (dashed) g.setLineDash([4 / zm.k, 3 / zm.k]);
    g.stroke();
    if (dashed) g.setLineDash([]);

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

  /* Canvas-2D stand-in when WebGL2 is unavailable: same culling indexes,
     straight-line edges, bounded edge count. Quality degrades, data does not. */
  function drawFallback(vx0, vy0, vx1, vy1, range) {
    const stamp = scene.nextEdgeStamp();
    let drawn = 0;
    g.strokeStyle = C_EDGE;
    g.lineWidth = Math.max(EDGE_MIN_PX / zm.k, 0.8);
    g.beginPath();
    const eadd = i => {
      if (drawn > FALLBACK_EDGE_MAX || scene.eStamp[i] === stamp) return;
      scene.eStamp[i] = stamp;
      if (scene.ex1[i] < vx0 || scene.ex0[i] > vx1 ||
          scene.ey1[i] < vy0 || scene.ey0[i] > vy1) return;
      drawn++;
      const a = N[E[i].s], t = N[E[i].t];
      g.moveTo(a.x, a.y + a.hh + 2);
      g.lineTo(t.x, t.y - t.hh - 4);
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
    g.stroke();

    for (let i = 0; i < scene.byRad.length; i++) {
      const u = scene.byRad[i], nd = N[u];
      if (nd.scc) continue;
      if (nd.x + nd.rad < vx0 || nd.x - nd.rad > vx1 ||
          nd.y + nd.rad < vy0 || nd.y - nd.rad > vy1) continue;
      const rpx = nd.rad * zm.k;
      g.fillStyle = scene.fills[u];
      if (rpx < 3) {
        const p = Math.max(1 / zm.k, nd.rad * 2);
        g.fillRect(nd.x - p / 2, nd.y - p / 2, p, p);
        continue;
      }
      g.globalAlpha = 0.85;
      g.beginPath();
      g.arc(nd.x, nd.y, nd.rad, 0, 6.2831853);
      g.fill();
      g.globalAlpha = 1;
      if (rpx < 6) continue;
      g.strokeStyle = nd.roots ? C_ROOT_STROKE : C_NODE_STROKE;
      g.lineWidth = (nd.roots ? 1.6 : 0.8) / zm.k;
      g.stroke();
    }

    if (selList) {
      for (const [ei, out] of selList) {
        const e = E[ei], a = N[e.s], t = N[e.t];
        g.strokeStyle = out ? C_EDGE_OUT : C_EDGE_IN;
        g.lineWidth = Math.max(1.25 / zm.k, scene.ew[ei] * 1.5);
        g.beginPath();
        g.moveTo(a.x, a.y + a.hh + 2);
        g.lineTo(t.x, t.y - t.hh - 4);
        g.stroke();
      }
    }
  }

  function draw() {
    if (!active()) return;
    const t0 = performance.now();
    const dpr = window.devicePixelRatio || 1;
    const w = W(), h = H();
    const vx0 = -zm.x / zm.k, vy0 = -zm.y / zm.k;
    const vx1 = vx0 + w / zm.k, vy1 = vy0 + h / zm.k;
    const range = scene.grid.range(vx0, vy0, vx1, vy1);
    if (glr) {
      updateCull(vx0, vy0, vx1, vy1, range);
      glr.draw(zm, w, h, dpr);
    }

    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, w, h);
    g.translate(zm.x, zm.y);
    g.scale(zm.k, zm.k);

    if (!glr) drawFallback(vx0, vy0, vx1, vy1, range);

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

    // SCC containers. Wide views scan the flat list (the bbox grid index
    // would mean touching every allocated cell); narrow views use the index,
    // which also de-duplicates boxes spanning several cells.
    const labeled = [];
    if (range) {
      const rangeCells = (range.cx1 - range.cx0 + 1) * (range.cy1 - range.cy0 + 1);
      const paint = u => {
        if (!drawSccNode(u) && N[u].rad * zm.k >= LABEL_R) labeled.push(u);
      };
      if (rangeCells > sccIds.length) {
        for (const u of sccIds) {
          const nd = N[u];
          if (nd.x + nd.hw < vx0 || nd.x - nd.hw > vx1 ||
              nd.y + nd.hh < vy0 || nd.y - nd.hh > vy1) continue;
          paint(u);
        }
      } else {
        const stamp = scene.nextNodeStamp();
        for (let cx = range.cx0; cx <= range.cx1; cx++) {
          for (let cy = range.cy0; cy <= range.cy1; cy++) {
            const cell = scene.grid.index(cx, cy);
            for (let p = scene.boxOff[cell]; p < scene.boxOff[cell + 1]; p++) {
              const u = scene.boxIds[p];
              if (scene.nStamp[u] === stamp) continue;
              scene.nStamp[u] = stamp;
              paint(u);
            }
          }
        }
      }
    }

    // Plain-node labels: radius-descending prefix of nodes big enough on
    // screen, then viewport filter. The prefix is tiny except at deep zoom.
    const needRad = LABEL_R / zm.k;
    for (let i = 0; i < scene.byRad.length && labeled.length < LABEL_MAX; i++) {
      const u = scene.byRad[i];
      if (scene.radOf[u] < needRad) break;
      const nd = N[u];
      if (nd.scc) continue;
      if (nd.x + nd.rad < vx0 || nd.x - nd.rad > vx1 ||
          nd.y + nd.rad < vy0 || nd.y - nd.rad > vy1) continue;
      labeled.push(u);
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

    // Selection arrowheads for edges wide enough to carry them.
    if (selList && selList.length <= 2000) {
      for (const [ei, out] of selList) {
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
    dbg.frameMs = Math.round((performance.now() - t0) * 10) / 10;
  }

  function resize() {
    if (!active()) return;
    const nextW = W(), nextH = H();
    zm = resizeTransform(zm, viewW, viewH, nextW, nextH, layout.width, layout.height, customView);
    const changed = nextW !== viewW || nextH !== viewH;
    viewW = nextW;
    viewH = nextH;
    const dpr = window.devicePixelRatio || 1;
    canvas.width = Math.max(1, Math.round(nextW * dpr));
    canvas.height = Math.max(1, Math.round(nextH * dpr));
    if (changed) scheduleLabels();
    schedule();
  }

  function showNode(u) {
    if (!active()) return;
    shown = u;
    const outIds = adjSlice(scene.outAdj, u);
    const inIds = adjSlice(scene.inAdj, u);
    selList = [...outIds.map(ei => [ei, true]), ...inIds.map(ei => [ei, false])];
    if (glr) glr.setSelection(outIds, inIds);
    if (hooks.onSelect) {
      hooks.onSelect(u, { outbound: outIds, inbound: inIds });
    } else {
      if (refsCleanup) refsCleanup();
      side.innerHTML = nodeDetailHead(u, vm, ctx);
      refsCleanup = mountRefLists(side, u, vm, ctx, scene.outAdj, scene.inAdj, adjSlice);
      const open = side.querySelector(".gopen");
      if (open) open.onclick = () => ctx.onOpenViz("flow", vm.dumpId, N[u].label);
    }
    schedule();
  }

  function reset() {
    if (!active()) return;
    shown = -1;
    selList = null;
    if (glr) glr.setSelection(null, null);
    if (refsCleanup) refsCleanup();
    refsCleanup = null;
    if (hooks.onReset) hooks.onReset();
    else if (side) side.innerHTML = defaultHtml;
    schedule();
  }

  function onWheel(e) {
    if (!active()) return;
    e.preventDefault();
    if (e.ctrlKey || e.shiftKey) {
      const rc = canvas.getBoundingClientRect();
      zoomAt(e.clientX - rc.left, e.clientY - rc.top, e.deltaY < 0 ? 1.18 : 1 / 1.18);
    } else {
      customView = true;
      zm.x -= e.deltaX;
      zm.y -= e.deltaY;
    }
    schedule();
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
      customView = true;
      zm.x = drag.zx + dx;
      zm.y = drag.zy + dy;
      schedule();
      scheduleLabels();
    }
  }

  function onUp() {
    if (!active()) return;
    drag = null;
    canvas.classList.remove("panning");
  }

  function onKey(e) {
    if (!active() || document.activeElement !== canvas || !canvas.getClientRects().length ||
        document.querySelector('[role="dialog"][aria-modal="true"]')) return;
    const t = e.target;
    if (t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.isContentEditable)) return;
    if (e.key === "+" || e.key === "=") zoomAt(W() / 2, H() / 2, 1.25);
    else if (e.key === "-" || e.key === "_") zoomAt(W() / 2, H() / 2, 0.8);
    else if (e.key === "0") fit();
    else return;
    schedule();
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

  function runControl(fn) {
    if (!active()) return;
    fn();
    schedule();
    scheduleLabels();
  }

  function toolButton(txt, title, cls, fn) {
    if (!tools) return;
    const b = document.createElement("button");
    b.textContent = txt;
    b.title = title;
    if (cls) b.className = cls;
    b.onclick = () => runControl(fn);
    tools.appendChild(b);
  }

  // Build the static panel once; selection only replaces it on demand.
  if (side) {
    defaultHtml = defaultSide(vm, ctx);
    side.innerHTML = defaultHtml;
  }
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
    scheduleLabels();
  } catch (e) {
    cleanup();
    throw e;
  }
  cleanup.controls = {
    zoomIn: () => runControl(() => zoomAt(W() / 2, H() / 2, 1.25)),
    zoomOut: () => runControl(() => zoomAt(W() / 2, H() / 2, 0.8)),
    fit: () => runControl(fit),
  };
  return cleanup;
}
