/* Flow viz — side panel (click detail): HTML builders only. The caller
   (render.js) sets innerHTML and wires the buttons/edge highlight. fx =
   {fxB, fxN, G, softWrap} — scale-aware formatters owned by render.js.
   See index.js for the module map. */

/* The default (nothing selected) summary. */
export function defaultSide(vm, ctx, G) {
  const layout = vm.layout, E = layout.edges;
  const liveCount = layout.nodes.filter(n => !n.dropped).length;
  return `<div class="gdim">${liveCount} ${vm.split ? "copies" : "classes"} (top by retained) · ${E.length} connections · ${layout.layerCount} layers` +
    `${layout.colCount ? ` · ${layout.colCount} pinned in the right column` : ""}` +
    `${layout.nestCount ? ` · ${layout.nestCount} nested into their owners` : ""}` +
    `${layout.cycleCount ? ` · ${layout.cycleCount} cycle edges` : ""}` +
    `${layout.dropCount ? ` · <b>${layout.dropCount} not shown</b> — not reachable downward from the root (their holders are cut by the top filter or sit outside the retained set)` : ""}` +
    `${vm.minR ? ` · filter: ≥ ${ctx.esc(ctx.fmtB(vm.minR))} retained` : ""}` +
    `${vm.split ? ` · <b>split</b>: one copy per distinct holder-class set` : ""}` +
    `${G() ? ` · numbers extrapolated per-instance × ${ctx.esc(ctx.fmtN(vm.objCount))}` : ""}.</div>` +
    `<div class="fnote">Click a node for its holder/held breakdown and to pin/unpin it. ` +
    `The <b>?</b> button in the toolbar explains how to read this graph.</div>`;
}

/* One selected node. u = layout node index; the callers wire .gopen / .gpinb. */
export function nodeDetail(u, vm, ctx, fx) {
  const layout = vm.layout, N = layout.nodes;
  const d = vm.details[u], nd = N[u];
  const pctR = vm.setBytes && d.rincl ? (() => { const p = 100 * d.rincl / vm.setBytes;
    return p >= 0.05 ? `${p >= 10 ? p.toFixed(0) : p.toFixed(1)}% of the retained set` : "<0.1% of the retained set"; })() : "";
  const row = l => `<div class="gref"><div class="gref-cls" title="${ctx.esc(l.cls)}">${fx.softWrap(ctx.esc(l.short))}</div>` +
    `<div class="gref-meta"><span class="f" title="${ctx.esc(l.f)}">${fx.softWrap(ctx.esc(l.f))}</span>` +
    `<span class="n">×${ctx.esc(ctx.fmtN(l.n))} · ${ctx.esc(fx.fxB(l.b))}</span></div></div>`;
  const where = d.col ? "pinned column · outbound refs listed, not drawn · "
    : d.nestedIn >= 0 ? `nested in ${ctx.esc(vm.details[d.nestedIn].short)} (back-ref) · ` : "";
  const pinBtn = d.col ? `<button class="gpinb" data-act="unpin">unpin from column</button>`
    : d.nestedIn < 0 && u !== layout.rootTop ? `<button class="gpinb" data-act="pin">pin to column</button>` : "";
  // retained breakdown: inclusive retained is the displayed (node-size) metric;
  // the dominator-retained number stays for reference. Pre-reach extractions
  // (no rincl columns) show the dominator number only.
  const retainedRows = vm.hasReach
    ? `<div class="gmrow"><span>retained (incl. shared)</span><span class="v"><b>${ctx.esc(fx.fxB(d.rincl))}</b>${pctR ? ` · ${ctx.esc(pctR)}` : ""}</span></div>
       <div class="gmrow"><span>exclusive</span><span class="v">${ctx.esc(fx.fxB(d.rincl - d.rsh))}</span></div>
       <div class="gmrow"><span>shared (wedge)</span><span class="v">${ctx.esc(fx.fxB(d.rsh))}${d.rincl ? ` · ${(100 * d.rsh / d.rincl).toFixed(d.rsh * 10 >= d.rincl ? 0 : 1)}%` : ""}</span></div>
       <div class="gmrow"><span>dominator retained</span><span class="v">${ctx.esc(fx.fxB(d.r))}</span></div>`
    : `<div class="gmrow"><span>retained</span><span class="v"><b>${ctx.esc(fx.fxB(d.r))}</b>${pctR ? ` · ${ctx.esc(pctR)}` : ""}</span></div>`;
  const holdersRow = vm.split && d.holders !== undefined
    ? `<div class="gmeta gdim">${d.holders === null ? "held by other holders (small copies folded into this residual)"
        : d.holders.length ? `held by ${ctx.esc(d.holders.map(h => ctx.shortClass(h)).join(", "))}`
        : "no holder inside the extracted set"}</div>`
    : "";
  return `<h5>${ctx.esc(d.short)}</h5>
    <div class="gfull">${ctx.esc(d.cls)}</div>
    ${holdersRow}
    <div class="gmeta">
      ${retainedRows}
      <div class="gmrow"><span>shallow</span><span class="v">${ctx.esc(fx.fxB(d.s))}</span></div>
      <div class="gmrow"><span>objects</span><span class="v">${ctx.esc(fx.fxN(d.n))}${fx.G() ? " (est.)" : ""}</span></div>
    </div>
    <div class="gmeta gdim">${where}layer ${d.layer} · held by ${d.ins.length} class${d.ins.length !== 1 ? "es" : ""} · holds ${d.outs.length}${d.nestCount ? ` · ${d.nestCount} nested inside` : ""}</div>
    <div class="gopenrow"><button class="pri gopen">open class ▸</button>${pinBtn}</div>
    <div class="glab">held by (inbound refs — shared when many)</div><div class="grefs">${d.ins.map(row).join("") || '<div class="gdim">—</div>'}</div>
    <div class="glab">holds (outbound refs)</div><div class="grefs">${d.outs.map(row).join("") || '<div class="gdim">—</div>'}</div>`;
}
