/* Flow viz — data step: fetch the anatomy payload and build the viewModel.
   params = {top, samples, scale, objCount, cyc, pins, minR, pinsOpen, split} —
   interactive controls round-trip through ctx.refetch(params), which re-runs
   prepare. See index.js for the module map. */

import { fmtB, fmtN, catOf, shortClass, scaleFactor } from "../common.js";
import { findClassStats } from "../anatomy.js";
import { computeFlowLayout, DEFAULT_TOP } from "./layout.js";
import { loadPins } from "./controls.js";

const DETAIL_ROWS = 14;    // max inbound/outbound rows in the click detail panel

export async function prepare(repo, dumpId, className, params = {}) {
  const res = await repo.anatomy(dumpId, className, { samples: params.samples ?? null });
  if (!res.ok) {
    if (res.status === 404) return { kind: "flow", dumpId, className, notAnalyzed: true };
    return { kind: "flow", dumpId, className, error: res.error || "anatomy query failed" };
  }
  const a = res.data;
  if (!a || !a.graph || !a.graph.nodes) return { kind: "flow", dumpId, className, notAnalyzed: true };

  // split copies (holder-set keying) ship as graph.split: same row shape plus a
  // holders column; reach columns (rincl/rshared) ride on both node sets
  const hasSplit = !!a.graph.split;
  const split = !!(params.split && hasSplit);
  const gin = split ? a.graph.split : a.graph;
  const hasReach = gin.nodes.length > 0 && gin.nodes[0].length > 4;

  const maxNodes = gin.nodes.length;
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
  const layout = computeFlowLayout(gin, { top, root: className, pins, minR: effMinR });
  const nodes = layout.nodes;
  nodes.forEach(nd => { nd.cat = catOf(nd.cls); });

  const labels = nodes.map(nd => {
    const s = shortClass(nd.cls);
    return s.length > 30 ? s.slice(0, 29) + "…" : s;
  });
  const details = nodes.map((nd, u) => ({
    cls: nd.cls, short: shortClass(nd.cls), n: nd.n, s: nd.s, r: nd.r,
    rincl: nd.rincl, rsh: nd.rsh, holders: nd.holders,
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
    const viaNote = e.via ? `\nvia filtered-out classes: ${e.via.slice(0, 3).map(c => shortClass(c)).join(", ")}` +
      (e.via.length > 3 ? ` +${e.via.length - 3} more` : "") : "";
    if (e.bi) {
      e.tip = `${nm(e.os)} → ${nm(e.ot)}: ×${fmtN(e.on)}, ${fmtB(e.ob)}\n` +
              `${nm(e.ros)} → ${nm(e.rot)}: ×${fmtN(e.rn)}, ${fmtB(e.rb)}` + viaNote;
    } else {
      e.tip = `${nm(e.os)} → ${nm(e.ot)}: ×${fmtN(e.n)}, ${fmtB(e.b)}` +
              (e.cyc ? "\nclass-level cycle edge (points up)" : "") + viaNote;
    }
  });

  return { kind: "flow", dumpId, className, samples: a.samples, available: a.available || [],
           objCount, scale, factor, cyc: params.cyc !== false, pinsOpen: !!params.pinsOpen,
           top, maxNodes, pins, minR, split, hasSplit, hasReach,
           setBytes: layout.setBytes, layout, labels, details };
}
