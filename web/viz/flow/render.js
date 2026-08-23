/* Flow viz — dumb renderer: SVG building + pan/zoom + click detail, from the
   viewModel only. Data-changing interactions (top-N, sample count, pins,
   split) go through ctx.refetch(params). Controls live in controls.js, the
   side-panel HTML in detail.js. See index.js for the module map. */

import { COL_SEP_DX, LABEL_RANK_MAX } from "./layout.js";
import { buildControls, savePins } from "./controls.js";
import { defaultSide, nodeDetail } from "./detail.js";

const SVGNS = "http://www.w3.org/2000/svg"; // namespace for createElementNS

/* Shared-bytes wedge: an amber pie slice over the node circle, fraction
   rshared/rincl of the inclusive retained (a full slice = fully shared). */
function wedgePath(cx, cy, r, f) {
  if (f >= 0.995) {
    return `M ${cx} ${cy - r} A ${r} ${r} 0 1 1 ${cx} ${cy + r} A ${r} ${r} 0 1 1 ${cx} ${cy - r} Z`;
  }
  const a = 2 * Math.PI * f - Math.PI / 2;   // clockwise from 12 o'clock
  const x = (cx + r * Math.cos(a)).toFixed(2), y = (cy + r * Math.sin(a)).toFixed(2);
  return `M ${cx} ${cy} L ${cx} ${cy - r} A ${r} ${r} 0 ${f > 0.5 ? 1 : 0} 1 ${x} ${y} Z`;
}

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
  const softWrap = s => s.replace(/\$/g, "$\u200b").replace(/([a-z])([A-Z])/g, "$1\u200b$2");
  const fx = { fxB, fxN, G, softWrap };
  const setPins = np => { savePins(np); refetch({ pins: np }); };

  // ---- shell: column with controls on top, canvas + side panel below ----
  const col = document.createElement("div"); col.className = "gcol";
  const wrap = document.createElement("div"); wrap.className = "gwrap";
  const cwrap = document.createElement("div"); cwrap.className = "gcanvas-wrap";
  const svg = document.createElementNS(SVGNS, "svg"); svg.setAttribute("class", "gcanvas");
  const tools = document.createElement("div"); tools.className = "gtools";
  const side = document.createElement("div"); side.className = "gside";
  cwrap.appendChild(svg); cwrap.appendChild(tools);
  wrap.appendChild(cwrap); wrap.appendChild(side);
  col.appendChild(wrap);
  container.appendChild(col);

  let ctl = null;   // buildControls handle (pinsOpen fold round-trips via refetch)
  function refetch(patch) {
    ctx.refetch({ top: vm.top, samples: vm.samples, scale, objCount: vm.objCount,
                  cyc: cycOn, pins: vm.pins, minR: vm.minR, split: vm.split,
                  pinsOpen: ctl ? ctl.pinsOpen() : vm.pinsOpen, ...patch });
  }
  ctl = buildControls(col, cwrap, vm, ctx, {
    refetch: p => refetch(p),
    getScale: () => scale,
    onScale: s => { scale = s; ctl.syncSeg(); refreshSide(); },
  });

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
  const edgeCls = (e, hl) =>   // hl = "", "dim", "in", "out", "bi"
    "gedge" + (hl ? " " + hl : "") + (e.cyc ? " cyc" : "") + (e.via ? " via" : "");
  const edgeEls = E.map(e => {
    if (e.d === null) return null;
    const el = mk("path", { d: e.d, "class": edgeCls(e),
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
    const c = mk("circle", { cx: nd.x, cy: nd.y, r: nd.rad, fill: ctx.catColor(nd.cat),
                             "class": "gnode" + (u === layout.rootTop ? " froot" : "") +
                                      (nd.rincl < layout.rmax * 0.005 ? " tiny" : "") });
    if (nd.rsh > 0 && nd.rincl > 0) {   // the shared wedge rides on top
      mk("path", { d: wedgePath(nd.x, nd.y, nd.rad, nd.rsh / nd.rincl), "class": "gwedge" });
    }
    if (vm.split) {
      const ti = document.createElementNS(SVGNS, "title");
      ti.textContent = ctx.shortClass(nd.cls) + (nd.holders === null ? " — held by other holders (folded)"
        : nd.holders && nd.holders.length ? ` — held by ${nd.holders.map(h => ctx.shortClass(h)).join(", ")}`
        : " — no holder inside the extracted set");
      c.appendChild(ti);
    }
    return c;
  });
  // nested circles ride on top of their host; "+n" badge when capped
  N.forEach((nd, u) => {
    if (!nd.nest.length && !nd.nestMore) return;
    for (const m of nd.nest) {
      const mn = N[m];
      const c = mk("circle", { cx: mn.x, cy: mn.y, r: mn.rad, fill: ctx.catColor(mn.cat), "class": "fnest" });
      const ti = document.createElementNS(SVGNS, "title");
      ti.textContent = `${ctx.shortClass(mn.cls)} — nested in ${ctx.shortClass(nd.cls)} ` +
        `(holds a back-reference to it)\n${ctx.fmtN(mn.n)} objects · ${ctx.fmtB(mn.s)} shallow · ${ctx.fmtB(mn.rincl)} retained`;
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
  const rankOrd = [...N.keys()].filter(u => N[u].nestedIn === -1 && !N[u].dropped).sort((a, b) => N[b].rincl - N[a].rincl);
  const rankOf = new Map(rankOrd.map((u, i) => [u, i]));
  let zm, labRAF = 0;
  const drawLabels = () => {
    labRAF = 0;
    labG.textContent = "";
    const placed = [];
    // the selected node labels first and bright (a selected NESTED node labels
    // itself too — normally nested circles stay unlabeled), its neighbors normal
    const ord = shown >= 0 && !N[shown].dropped
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
    const t = e.target;   // typing 0/+/- into the min-retained or pin input must not zoom
    if (t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.isContentEditable)) return;
    if (e.key === "+" || e.key === "=") zoomAt(W() / 2, H() / 2, 1.25);
    else if (e.key === "-" || e.key === "_") zoomAt(W() / 2, H() / 2, 0.8);
    else if (e.key === "0") fit();
  };
  // labels live in screen space — a window resize invalidates their placement
  const onResize = () => {
    if (!svg.isConnected) { window.removeEventListener("resize", onResize); return; }
    queueLabels();
  };
  window.addEventListener("pointermove", onMove);
  window.addEventListener("pointerup", onUp);
  window.addEventListener("keydown", onKey);
  window.addEventListener("resize", onResize);
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
  const resetEdges = () => edgeEls.forEach((el, ri) => {
    if (!el) return;
    const e = E[ri];
    el.setAttribute("class", edgeCls(e));
    el.setAttribute("stroke-width", (e.cyc ? 1 : e.w).toFixed(2));
    setMarkers(el, e, "arr");
  });
  const showNode = u => {
    shown = u;
    const nd = N[u];
    side.innerHTML = nodeDetail(u, vm, ctx, fx);
    side.querySelector(".gopen").onclick = () => ctx.onOpenViz("anatomy", vm.dumpId, vm.details[u].cls);
    const pb = side.querySelector(".gpinb");
    if (pb) pb.onclick = () =>
      setPins(pb.dataset.act === "pin" ? [...vm.pins, vm.details[u].cls] : vm.pins.filter(x => x !== vm.details[u].cls));
    selRing.setAttribute("cx", nd.x); selRing.setAttribute("cy", nd.y);
    selRing.setAttribute("r", nd.rad + 4); selRing.removeAttribute("display");
    nb = new Set();
    edgeEls.forEach((el, ri) => {
      if (!el) return;
      const e = E[ri];
      const isS = e.s === u || e.os === u || (e.bi && e.ros === u);
      const isT = e.t === u || e.ot === u || (e.bi && e.rot === u);
      if (!isS && !isT) {
        el.setAttribute("class", edgeCls(e, "dim"));
        el.setAttribute("stroke-width", (e.cyc ? 1 : e.w).toFixed(2));
        setMarkers(el, e, "arr");
        return;
      }
      nb.add(e.s); nb.add(e.t);
      const ek = e.bi ? "bi" : (isT ? "in" : "out");
      el.setAttribute("class", edgeCls(e, ek));
      el.setAttribute("stroke-width", (1.2 + 3.3 * Math.sqrt(e.b / layout.emax)).toFixed(2));
      setMarkers(el, e, "arr-" + ek);
    });
    queueLabels();
  };
  const refreshSide = () => { if (shown >= 0 && vm.details[shown]) showNode(shown); else side.innerHTML = defaultSide(vm, ctx, G); };
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
      resetEdges(); side.innerHTML = defaultSide(vm, ctx, G); queueLabels();
    }
  });
}
