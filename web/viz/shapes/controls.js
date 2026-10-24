/* Shapes viz — analysis and metric controls. This module owns DOM controls
   only; starting/polling analysis remains behind ctx.analyze in viz/common. */

import { buildSeg } from "../common.js";

/* Analysis settings are explicit: scope and container semantics are not
   inferred from an old artifact. Re-analysis replaces the class artifact. */
export function analysisControls(vm, ctx) {
  const row = document.createElement("div");
  row.className = "viz-anrow";
  if (!ctx.analyze) return row;
  const scope = document.createElement("select");
  scope.setAttribute("aria-label", "shapes scope");
  for (const value of ["retained", "reachable"]) {
    const opt = document.createElement("option");
    opt.value = value;
    opt.textContent = value;
    scope.appendChild(opt);
  }
  scope.value = vm.requestedScope || "retained";
  const mode = document.createElement("select");
  mode.setAttribute("aria-label", "map structure");
  for (const [value, text] of [["normalized", "maps: unordered entries"], ["raw", "maps: raw topology"]]) {
    const opt = document.createElement("option");
    opt.value = value;
    opt.textContent = text;
    mode.appendChild(opt);
  }
  mode.value = vm.normalizeMaps === false ? "raw" : "normalized";
  const btn = document.createElement("button");
  btn.textContent = vm.notAnalyzed ? "analyze (exact)" : "re-analyze";
  const status = document.createElement("span");
  status.className = "viz-anstatus";
  btn.onclick = () => {
    btn.disabled = true;
    ctx.analyze((text, isErr) => {
      status.textContent = text;
      status.classList.toggle("err", !!isErr);
      if (isErr) btn.disabled = false;
    }, null, { scope: scope.value, normalizeMaps: mode.value === "normalized" });
  };
  row.append(scope, mode, btn, status);
  return row;
}

/* Exact shapes analysis has no sample sizes, so the shared sample-picker
   prompt does not apply. */
export function notAnalyzedPane(vm, ctx) {
  const d = document.createElement("div");
  d.className = "gnote";
  const msg = document.createElement("div");
  msg.textContent = "No shapes analysis for this class yet — the exact structural " +
    "collapse (SHAPES.md) runs over every instance of the class, not a sample.";
  d.appendChild(msg);
  if (!ctx.analyze) {
    const hint = document.createElement("div");
    hint.className = "hint";
    hint.textContent = "Run the shapes analysis on the server, then reopen this viz.";
    d.appendChild(hint);
    return d;
  }
  d.appendChild(analysisControls(vm, ctx));
  return d;
}

export function shapesToolbar(vm, ctx) {
  const bar = document.createElement("div");
  bar.className = "fbar";
  const grp = document.createElement("div");
  grp.className = "fgroup";
  const lab = document.createElement("span");
  lab.className = "flab";
  lab.textContent = "plain node size";
  grp.appendChild(lab);
  grp.appendChild(buildSeg([
    { value: "shallow", label: "shallow", title: "total shallow bytes of the shape's instances" },
    { value: "count", label: "objects", title: "collapsed object count" },
    { value: "roots", label: "roots", title: "instances of the queried class" },
  ], vm.metric, v => ctx.refetch({ metric: v })));
  bar.appendChild(grp);
  const note = document.createElement("span");
  note.className = "flab";
  note.textContent = `exact structural collapse, ${ctx.fmtN(vm.shapeCount)} shapes`;
  bar.appendChild(note);
  bar.appendChild(analysisControls(vm, ctx));
  return bar;
}
