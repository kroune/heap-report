/* Shapes viz — renderer composition. controls.js owns toolbar/analysis DOM,
   details.js owns side-panel HTML, scene.js owns pure indexes, and canvas.js
   owns painting, interaction and resource cleanup. No fetch or repo imports. */

import { mountShapesCanvas } from "./canvas.js";
import { notAnalyzedPane, shapesToolbar } from "./controls.js";

export function render(container, vm, ctx) {
  container.textContent = "";
  if (vm.notAnalyzed || vm.error) {
    if (vm.error) {
      const d = document.createElement("div");
      d.className = "gnote gerr";
      d.textContent = vm.error;
      container.appendChild(d);
    } else {
      container.appendChild(notAnalyzedPane(vm, ctx));
    }
    return () => { container.textContent = ""; };
  }

  const col = document.createElement("div");
  col.className = "gcol";
  const wrap = document.createElement("div");
  wrap.className = "gwrap";
  const cwrap = document.createElement("div");
  cwrap.className = "gcanvas-wrap";
  const canvas = document.createElement("canvas");
  canvas.className = "gcanvas";
  const tools = document.createElement("div");
  tools.className = "gtools";
  const side = document.createElement("div");
  side.className = "gside";
  cwrap.appendChild(canvas);
  cwrap.appendChild(tools);
  wrap.appendChild(cwrap);
  wrap.appendChild(side);
  col.appendChild(shapesToolbar(vm, ctx));
  col.appendChild(wrap);
  container.appendChild(col);

  const cleanupCanvas = mountShapesCanvas(canvas, tools, side, vm, ctx);
  return () => {
    cleanupCanvas();
    container.textContent = "";
  };
}
