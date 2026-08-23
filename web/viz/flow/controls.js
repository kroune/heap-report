/* Flow viz — controls: toolbar groups, pins editor, help overlay, plus the
   pin-set storage (localStorage) shared with prepare.js. Pure DOM building
   from the viewModel; every data-changing action goes through opts.refetch /
   opts.onScale. See index.js for the module map. */

import { MIN_TOP } from "./layout.js";

const PINS_KEY = "heap-report.flow.pins.v1";    // localStorage key for the pin set

export const DEFAULT_PINS = [
  "java.lang.String", "java.lang.Object[]",
  "byte[]", "char[]", "short[]", "int[]", "long[]", "float[]", "double[]", "boolean[]",
  "java.util.HashMap", "java.util.LinkedHashMap", "java.util.concurrent.ConcurrentHashMap",
];

/* The pin set: one global list, user-edited. Guards: sandboxed frames can throw
   on localStorage access, and a corrupt value falls back to defaults. */
export function loadPins() {
  try {
    const v = JSON.parse(localStorage.getItem(PINS_KEY) || "null");
    if (Array.isArray(v) && v.every(x => typeof x === "string")) return v;
  } catch (e) { /* no storage available — defaults */ }
  return [...DEFAULT_PINS];
}
export function savePins(p) {
  try { localStorage.setItem(PINS_KEY, JSON.stringify(p)); } catch (e) { /* no storage */ }
}

/* "5m" / "500k" / "1.5g" / "8000000" -> bytes; null when unparseable */
export const parseBytes = s => {
  const m = /^\s*(\d+(?:\.\d+)?)\s*([kmg])?b?\s*$/i.exec(s);
  if (!m) return null;
  const mult = { "": 1, k: 1 << 10, m: 1 << 20, g: 1 << 30 }[(m[2] || "").toLowerCase()];
  return Math.round(parseFloat(m[1]) * mult);
};

/* Toolbar (numbers/samples/top/min-retained/holders groups + pins/help
   toggles), the pins editor fold, and the help overlay. Appends to `col`
   (bar + pinbar) and `cwrap` (help overlay). opts = {refetch, getScale,
   onScale}; onScale("sample"|"global") flips the displayed numbers without a
   refetch (getScale feeds the segment highlight). Returns {pinsOpen} so the
   caller can round-trip the fold in refetch params. */
export function buildControls(col, cwrap, vm, ctx, opts) {
  const refetch = opts.refetch;
  let pinsOpen = vm.pinsOpen;   // the pin editor fold survives refetch via the params

  // ---- toolbar: labeled groups on the left, pins/help toggles on the right ----
  const bar = document.createElement("div"); bar.className = "fbar";
  col.appendChild(bar);
  const mkGroup = label => {
    const g = document.createElement("div"); g.className = "fgroup";
    const s = document.createElement("span"); s.className = "flab"; s.textContent = label;
    g.appendChild(s); bar.appendChild(g);
    return g;
  };

  const gScale = mkGroup("numbers");
  const seg = document.createElement("div"); seg.className = "anatseg";
  const bSample = document.createElement("button");
  bSample.textContent = `${vm.samples} samples`;
  bSample.title = "numbers from the sampled instances, as extracted";
  const bGlobal = document.createElement("button");
  bGlobal.textContent = vm.objCount ? `× ${ctx.fmtN(vm.objCount)} est.` : "global (count unknown)";
  bGlobal.title = vm.objCount
    ? `per-instance averages extrapolated to all ${ctx.fmtN(vm.objCount)} instances of the class`
    : "instance count unknown — only the sample view is available";
  bGlobal.disabled = !vm.objCount;
  const syncSeg = () => {
    bSample.classList.toggle("on", opts.getScale() !== "global");
    bGlobal.classList.toggle("on", opts.getScale() === "global");
  };
  bSample.onclick = () => opts.onScale("sample");
  bGlobal.onclick = () => { if (vm.objCount) opts.onScale("global"); };
  syncSeg();
  seg.appendChild(bSample); seg.appendChild(bGlobal);
  gScale.appendChild(seg);
  if (vm.available.length > 1) {
    const gK = mkGroup("samples");
    const kseg = document.createElement("div"); kseg.className = "anatseg";
    for (const k of vm.available) {
      const b = document.createElement("button");
      b.textContent = `${k}`;
      b.classList.toggle("on", k === vm.samples);
      b.onclick = () => refetch({ samples: k });
      kseg.appendChild(b);
    }
    gK.appendChild(kseg);
  }
  const gTop = mkGroup("top");
  const rng = document.createElement("input");
  rng.type = "range"; rng.className = "gtop";
  rng.min = Math.min(MIN_TOP, vm.maxNodes); rng.max = vm.maxNodes; rng.value = vm.top;
  rng.title = "keep only the N biggest classes by retained bytes";
  const rngV = document.createElement("span"); rngV.className = "fval"; rngV.textContent = vm.top;
  rng.oninput = () => { rngV.textContent = rng.value; };
  rng.onchange = () => refetch({ top: +rng.value });
  gTop.appendChild(rng); gTop.appendChild(rngV);
  const gMin = mkGroup("min retained");
  const minIn = document.createElement("input");
  minIn.className = "gpinin gminr"; minIn.placeholder = "off (e.g. 5m)"; minIn.spellcheck = false;
  minIn.value = vm.minR ? ctx.fmtB(vm.minR) : "";
  minIn.title = "hide classes with less retained heap than this (k/m/g suffixes)";
  const minSt = document.createElement("span"); minSt.className = "ferr";
  const applyMin = () => {
    const v = minIn.value.trim();
    if (!v) { if (vm.minR) refetch({ minR: 0 }); return; }
    const b = parseBytes(v);
    if (b === null) { minSt.textContent = "not a size — try 5m / 500k / 1.5g"; return; }
    if (b !== vm.minR) refetch({ minR: b });
  };
  minIn.addEventListener("change", applyMin);
  minIn.addEventListener("keydown", e => { if (e.key === "Enter") applyMin(); });
  minIn.addEventListener("input", () => { minSt.textContent = ""; });
  gMin.appendChild(minIn); gMin.appendChild(minSt);
  const gSplit = mkGroup("holders");
  const bSplit = document.createElement("button");
  bSplit.className = "fbtn";
  bSplit.textContent = "split";
  bSplit.classList.toggle("on", vm.split);
  bSplit.title = vm.hasSplit
    ? "split each class into one copy per distinct set of direct holder classes " +
      "(who holds the weight vs who just references something already held)"
    : "re-analyze to enable — this extraction predates holder-set analysis";
  bSplit.disabled = !vm.hasSplit;
  bSplit.onclick = () => refetch({ split: !vm.split });
  gSplit.appendChild(bSplit);
  const spacer = document.createElement("span"); spacer.className = "fspacer";
  bar.appendChild(spacer);
  const pinTgl = document.createElement("button");
  pinTgl.className = "fbtn";
  pinTgl.title = "show/hide the pinned-classes editor (pins live in the right column)";
  const helpBtn = document.createElement("button");
  helpBtn.className = "fbtn"; helpBtn.textContent = "?"; helpBtn.title = "how to read this graph";
  bar.appendChild(pinTgl); bar.appendChild(helpBtn);

  // ---- pin editor: chips for every pinned class + free-form add (folded away) ----
  const N = vm.layout.nodes;
  const pinbar = document.createElement("div"); pinbar.className = "gpinbar fpins";
  const present = new Set(N.filter(nd => !nd.dropped).map(nd => nd.cls));
  const pinLab = document.createElement("span"); pinLab.className = "flab";
  pinLab.textContent = "pinned — drawn in the right column, outside the top-down layout:";
  pinbar.appendChild(pinLab);
  const setPins = np => { savePins(np); refetch({ pins: np }); };
  for (const p of vm.pins) {
    const chip = document.createElement("span");
    chip.className = "gpin" + (present.has(p) ? "" : " absent");
    chip.title = p + (present.has(p) ? "" : " (not in this graph)");
    chip.appendChild(document.createTextNode(ctx.shortClass(p)));
    const x = document.createElement("button");
    x.textContent = "×"; x.title = "unpin " + p;
    x.addEventListener("click", () => setPins(vm.pins.filter(q => q !== p)));
    chip.appendChild(x);
    pinbar.appendChild(chip);
  }
  const pinIn = document.createElement("input");
  pinIn.className = "gpinin"; pinIn.placeholder = "pin a class (full name)…";
  pinIn.spellcheck = false;
  const pinAdd = document.createElement("button");
  pinAdd.className = "gpinadd"; pinAdd.textContent = "add";
  const pinStatus = document.createElement("span"); pinStatus.className = "ferr";
  const addPin = () => {
    const v = pinIn.value.trim();
    if (!v) return;
    if (vm.pins.includes(v)) { pinStatus.textContent = "already pinned"; return; }
    setPins([...vm.pins, v]);
  };
  pinAdd.addEventListener("click", addPin);
  pinIn.addEventListener("keydown", e => { if (e.key === "Enter") addPin(); });
  pinIn.addEventListener("input", () => { pinStatus.textContent = ""; });
  const pinReset = document.createElement("button");
  pinReset.className = "gpinadd"; pinReset.textContent = "reset";
  pinReset.title = "back to the default pin set";
  pinReset.addEventListener("click", () => setPins([...DEFAULT_PINS]));
  pinbar.appendChild(pinIn); pinbar.appendChild(pinAdd); pinbar.appendChild(pinReset);
  pinbar.appendChild(pinStatus);
  const syncPinsUi = () => {
    pinTgl.textContent = `pinned · ${vm.pins.length}`;
    pinTgl.classList.toggle("on", pinsOpen);
    pinbar.hidden = !pinsOpen;
  };
  pinTgl.onclick = () => { pinsOpen = !pinsOpen; syncPinsUi(); };
  syncPinsUi();
  col.appendChild(pinbar);

  // ---- help overlay: the how-to-read guide, toggled by the ? button ----
  const help = document.createElement("div");
  help.className = "fhelp"; help.hidden = true;
  help.innerHTML = `<b>Controls</b><br>
    drag or scroll = pan · ctrl/shift+scroll = zoom · <b>+</b>/<b>−</b> zoom · <b>0</b> fit<br>
    click a node = detail + edge highlight · double-click = open the class anatomy<br><br>
    <b>How to read it</b><br>
    The inspected class sits on top; each layer is what the layers above hold; edges only point
    down — a class held by several others appears once, with edges converging into it.
    <b>Node size = INCLUSIVE retained: everything reachable DOWNWARD from the class's
    instances</b> — back-references to ancestors (a child's <i>owner</i> field, this$0) don't
    count: the parent shows its own weight when you look at it. A child can still be bigger
    than its parent when other classes reach the same objects.
    The <b>amber wedge</b> is the SHARED part of that: bytes reachable from ≥2 of the sampled
    instances (someone else holds them too); the solid rest is exclusive to this class.
    The detail panel breaks it down and keeps the dominator-retained number for reference.
    Purple ring = shared by ≥3 holder classes (holder count, not shared bytes).
    ⇄ = two-way class-level reference (drawn downward, arrowheads both ends); ↻ arc =
    self-reference. A small circle inside a node = a nested class (inner class, lambda) whose
    instances hold a back-reference to the outer one — the capture edge is implied by containment,
    not drawn. The right column = pinned common classes; edges to them route right and they break
    the top-down rule by design. Faint dashed edges are class-level cycle edges (they point up) —
    hide them with ⟲. Dotted edges are contracted: the reference runs through classes hidden by
    the filters (hover to see which). Zoom in to reveal more labels.<br><br>
    <b>holders → split</b> draws one copy of a class per distinct set of direct holder
    classes (exact per-copy numbers, objects shared by several holders form their own copy);
    the tooltip and detail panel name the holders. Blind spots, consistent with the sampled
    nature of the graph: sharing with UNSAMPLED instances and between two objects of the
    same class under one sampled root is invisible.`;
  cwrap.appendChild(help);
  helpBtn.onclick = () => { help.hidden = !help.hidden; helpBtn.classList.toggle("on", !help.hidden); };

  return { pinsOpen: () => pinsOpen, syncSeg };
}
