/* Flow viz (top-down layered DAG of the class-level reference graph, pinned
   column, nesting, holder-set split copies, shared-retained wedges). Fed by
   the anatomy payload (GET /api/dumps/{id}/anatomy?class=…&samples=…).

   Module map (web/CONTRACTS.md hard rules: named/namespace relative imports
   only, no re-exports, no top-level side effects — the snapshot bundler
   rewrites each module independently):

     layout.js    computeFlowLayout — pure: no DOM, no fetch, no input mutation
     prepare.js   data step: fetch + scale/extrapolation + viewModel
     controls.js  toolbar groups, pins editor, help overlay, pin storage
     detail.js    side panel (click detail) HTML builders
     render.js    canvas, edges, node rendering, interaction wiring
     index.js     this file: the viz module contract (kind/prepare/render) */

import * as prep from "./prepare.js";
import * as rend from "./render.js";

export const kind = "flow";

export async function prepare(repo, dumpId, className, params = {}) {
  return prep.prepare(repo, dumpId, className, params);
}

export function render(container, vm, ctx) {
  rend.render(container, vm, ctx);
}
