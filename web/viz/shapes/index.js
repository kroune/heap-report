/* Shapes viz (experimental) — the exact structural collapse (backend/mat/
   SHAPES.md, implemented by experiments/shapes): every object reachable from
   ALL instances of the queried class, collapsed into a shape-DAG. Fed by the
   shapes payload (GET /api/dumps/{id}/shapes?class=…, from the SHP1 artifact
   produced by the SHAPES job).

   Module map (web/CONTRACTS.md hard rules: named/namespace relative imports
   only, no re-exports, no top-level side effects — the snapshot bundler
   rewrites each module independently):

     layout.js    computeShapesLayout — pure DAG layout (no DOM, no fetch);
                  fully iterative + column-folded, runs the full 1.37M-shape
                  payload in ~3 s
     prepare.js   data step: fetch + viewModel
     render.js    Canvas-2D renderer (grid culling + overview bitmap + CSR
                  adjacency) + side panel (dumb: no fetch, no repo imports)
     index.js     this file: the viz module contract (kind/prepare/render)

   analyzeOp = "shapesAnalyze": the popup's analyze affordance (ctx.analyze in
   viz/common.js) queues the SHAPES job instead of the MAT sampling analysis. */

import * as prep from "./prepare.js";
import * as rend from "./render.js";

export const kind = "shapes";
export const analyzeOp = "shapesAnalyze";

export async function prepare(repo, dumpId, className, params = {}) {
  return prep.prepare(repo, dumpId, className, params);
}

export function render(container, vm, ctx) {
  rend.render(container, vm, ctx);
}
