/* Shapes viz — data step: fetch the SHP1-derived payload and build the
   viewModel. params = {metric} — interactive controls round-trip through
   ctx.refetch(params), which re-runs prepare. See index.js for the module map. */

import { catOf, shortClass } from "../common.js";
import { computeShapesLayout } from "./layout.js";

export async function prepare(repo, dumpId, className, params = {}) {
  const res = await repo.shapes(dumpId, className);
  if (!res.ok) {
    if (res.status === 404) return { kind: "shapes", dumpId, className, notAnalyzed: true };
    return { kind: "shapes", dumpId, className, error: res.error || "shapes query failed" };
  }
  const p = res.data;
  if (!p || !p.shapes || !p.shapes.length)
    return { kind: "shapes", dumpId, className, notAnalyzed: true };

  const nodes = p.shapes.map(s => {
    const plain = s.kind === "leaf" || s.kind === "node";   // label is a real class name
    // scc labels are the full member list ("scc[A,B,C]") — too long for the
    // canvas; show the first member + count (the full list stays in the detail)
    let short = plain ? shortClass(s.label) : s.label;
    if (!plain) {
      const inner = s.label.replace(/^scc!?\[|\]$/g, "").split(",");
      short = `${s.kind === "scc" ? "scc" : "scc!"}[${shortClass(inner[0])}${inner.length > 1 ? ` +${inner.length - 1}` : ""}]`;
    }
    return {
      id: s.id,
      label: s.label,
      short,
      plain,
      kind: s.kind,
      count: s.count,
      st: s.shallowTotal, smin: s.shallowMin, smax: s.shallowMax,
      roots: s.roots,
      sccSize: s.sccSize,
      internalEdges: s.internalEdges,
      edgesPerMember: s.edgesPerMember,
      cat: catOf(s.label),
    };
  });
  const layout = computeShapesLayout(nodes, p.edges, params.metric || "shallow");

  return {
    kind: "shapes", dumpId, className,
    metric: params.metric || "shallow",
    reachable: p.reachableCount,
    shapeCount: p.shapes.length,
    rootsHistogram: p.rootsHistogram,   // [[shapeId, count]] desc
    nodes, layout,
  };
}
