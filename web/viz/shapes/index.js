/* Shapes renderer — exact structural collapse of the complete non-class heap
   (backend/mat/SHAPES.md, implemented by experiments/shapes). The Shapes tab
   owns fetching, job polling and dump switching; this module is the reusable
   prepare/render pair. It is deliberately NOT in the class-viz registry.

   Module map (web/CONTRACTS.md hard rules: named/namespace relative imports
   only, no re-exports, no top-level side effects — the snapshot bundler
   rewrites each module independently):

     layout.js    computeShapesLayout — pure DAG layout (no DOM, no fetch);
                  fully iterative, top-down by default with adaptive compact
                  mode for extreme graphs
     prepare.js   data step: fetch + pure viewModel mapping
     scc-layout.js pure nested member/connection layout for complete SCCs
     controls.js  metric and exact-analysis controls
     details.js   summary, node detail and lazy reference lists
     scene.js     pure spatial/adjacency indexes and view transforms
     gl.js        WebGL2 bulk pass: every edge and plain node, every frame
     canvas.js    Canvas-2D overlay (SCC internals, labels, selection) and
                  interaction lifecycle; falls back to 2D when WebGL2 is missing
     render.js    renderer composition; returns cleanup
     index.js     this file: the Shapes tab renderer contract */

import * as prep from "./prepare.js";
import * as rend from "./render.js";

export async function prepare(repo, dumpId, params = {}) {
  return prep.prepare(repo, dumpId, params);
}

export function render(container, vm, ctx) {
  return rend.render(container, vm, ctx);
}
