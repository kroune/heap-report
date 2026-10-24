/* Shapes viz — the exact structural collapse (backend/mat/
   SHAPES.md, implemented by experiments/shapes): objects in the selected
   scope of ALL instances of the queried class, collapsed into a shape-DAG. Fed by the
   shapes payload (GET /api/dumps/{id}/shapes?class=…, from the SHP1 artifact
   produced by the SHAPES job).

   Module map (web/CONTRACTS.md hard rules: named/namespace relative imports
   only, no re-exports, no top-level side effects — the snapshot bundler
   rewrites each module independently):

     layout.js    computeShapesLayout — pure DAG layout (no DOM, no fetch);
                  fully iterative + column-folded, runs the full 1.37M-shape
                  payload in ~3 s
     prepare.js   data step: fetch + pure viewModel mapping
     scc-layout.js pure nested member/connection layout for complete SCCs
     controls.js  metric and exact-analysis controls
     details.js   summary, node detail and lazy reference lists
     scene.js     pure spatial/adjacency indexes and view transforms
     canvas.js    Canvas-2D painting, bitmap LOD and interaction lifecycle
     render.js    renderer composition; returns cleanup
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
  return rend.render(container, vm, ctx);
}
