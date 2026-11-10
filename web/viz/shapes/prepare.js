/* Shapes viz — data step: fetch the SHP1-derived payload and build the
   viewModel. shapesViewModel is pure so payload mapping and layout can be
   tested without a DOM or repository. Interactive params round-trip through
   ctx.refetch(params), which re-runs prepare. */

import { catOf, shortClass } from "../common.js";
import { computeShapesLayout } from "./layout.js";

function baseModel(dumpId, params) {
  return {
    kind: "shapes", dumpId,
    layoutMode: params.layoutMode || "auto",
  };
}

export function shapesViewModel(p, dumpId, params = {}) {
  if (!p || !Array.isArray(p.shapes) || !p.shapes.length)
    return { ...baseModel(dumpId, params), notAnalyzed: true };

  const nodes = p.shapes.map(s => {
    const plain = s.kind === "leaf" || s.kind === "node";   // label is a real class name
    const members = Array.isArray(s.members) ? s.members.map(m => ({
      label: m.label, short: shortClass(m.label), cat: catOf(m.label),
      internal: m.internal || [],
    })) : null;
    // SCC labels are the full member list; the full list stays in the detail.
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
      members,
      sccComplete: s.membersComplete === true && !!members && members.length === s.sccSize,
      internalEdges: s.internalEdges,
      edgesPerMember: s.edgesPerMember,
      cat: catOf(s.label),
    };
  });
  const metric = params.metric || "shallow";
  const layout = computeShapesLayout(nodes, p.edges || [], metric, params.layoutMode || "auto");
  const analysis = p.analysis || null;

  return {
    ...baseModel(dumpId, params),
    metric,
    reachable: p.reachableCount ?? 0,
    shapeCount: p.shapes.length,
    analysis,
    nodes, layout,
  };
}

export async function prepare(repo, dumpId, params = {}) {
  const res = await repo.shapes(dumpId);
  if (!res.ok) {
    if (res.status === 404)
      return { ...baseModel(dumpId, params), notAnalyzed: true };
    return { kind: "shapes", dumpId, error: res.error || "shapes query failed" };
  }
  return shapesViewModel(res.data, dumpId, params);
}
