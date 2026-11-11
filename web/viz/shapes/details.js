/* Shapes viz — HTML side-panel view builders and lazy reference lists.
   All data-derived HTML is escaped with the shared five-character esc().
   Large edge lists are batched on scroll, never capped. */

export function defaultSide(vm, ctx) {
  const total = vm.nodes.reduce((a, n) => a + n.st, 0);
  const analysis = vm.analysis;
  const extractionMode = analysis ? analysis.extractionMode.type : "legacy heap";
  return `<div class="gdim">${ctx.esc(extractionMode)}${analysis ? "" : " — re-analyze to use the new pipeline"}</div>` +
    `<div class="gdim">${vm.shapeCount} shapes over ${ctx.esc(ctx.fmtN(vm.reachable))} ` +
    `heap objects · ${ctx.esc(ctx.fmtB(total))} shallow · ${vm.layout.layerCount} layers.</div>` +
    `<div class="fnote">Exact: every non-class heap object collapsed by structure — no sampling. ` +
    `Plain node size = ${vm.metric === "count" ? "collapsed object count" : "shallow total"}; ` +
    `SCC boxes fit their member graph; edge width = projected reference count. ` +
    `Zoom into an SCC to see every stored member ` +
    `and internal connection. Click a shape for details, double-click a plain class to open its flow. ` +
    `Sub-pixel detail is shown as dots until you zoom in — nothing is removed.</div>` +
    (analysis ? `<div class="gdim">Source refs in heap: ${ctx.esc(ctx.fmtN(analysis.sourceEdges))}; ` +
      `projected refs: ${ctx.esc(ctx.fmtN(analysis.projectedEdges))}.</div>` : "") +
    (analysis && analysis.relaxation ? `<div class="gdim">Structural relaxation: ` +
      `${ctx.esc(analysis.relaxation.file || "rules")}@${ctx.esc(analysis.relaxation.sha1 || "?")}; ` +
      `${ctx.esc(ctx.fmtN((analysis.relaxation.classOnlyHiddenEdges || 0) +
        (analysis.relaxation.setChildrenHiddenEdges || 0)))} concealed refs, ` +
      `${ctx.esc(ctx.fmtN(analysis.relaxation.setChildrenConcealedObjects || 0))} quotient members. ` +
      `Population and byte totals remain exact.</div>` : "");
}

export function nodeDetailHead(u, vm, ctx) {
  const nd = vm.nodes[u];
  const kb = { leaf: "leaf — no children in the selected scope",
               node: "node — keyed by (class, child shapes)",
               scc: "merged SCC — collapsed cycle" }[nd.kind] || nd.kind;
  const mrow = (k, v) => `<div class="gmrow"><span>${k}</span><span class="v">${v}</span></div>`;
  const sccNote = nd.kind !== "scc" ? "" :
    nd.sccComplete ? `<div class="gdim gmeta">${nd.members.length} canonical members and ` +
      `${nd.internalEdges} internal references are rendered inside the SCC box.</div>` :
    nd.members ? `<div class="gdim gmeta">This oversized SCC has an incomplete member graph ` +
      `(${nd.members.length} of ${nd.sccSize} member labels stored), so it cannot be rendered safely.</div>` :
    `<div class="gdim gmeta">This legacy artifact has no SCC member graph. Re-analyze to render ` +
      `members and internal connections.</div>`;
  return `<h5 title="${ctx.esc(nd.label)}">${ctx.esc(nd.short)} <span class="gdim">#${u}</span></h5>` +
    `<div class="gfull">${ctx.esc(nd.label)}</div>` +
    `<div class="gdim gmeta">${ctx.esc(kb)}</div>` +
    mrow("objects", ctx.esc(ctx.fmtN(nd.count)) +
      (nd.sccSize > 1 ? ` = ${ctx.esc(ctx.fmtN(Math.round(nd.count / nd.sccSize)))} instances × ${nd.sccSize}` : "")) +
    mrow("shallow total", ctx.esc(ctx.fmtB(nd.st))) +
    mrow("shallow min … max", `${ctx.esc(ctx.fmtB(nd.smin))} … ${ctx.esc(ctx.fmtB(nd.smax))}`) +
    (nd.internalEdges ? mrow("internal edges", `${nd.internalEdges} per instance (inside the cycle)`) : "") +
    sccNote +
    (nd.plain ? `<div class="gopenrow"><button class="gopen">open flow ▸</button></div>` : "");
}

/* Append BATCH rows whenever the panel scrolls near the bottom. The returned
   cleanup removes the scroll listener when selection or renderer changes. */
export function mountRefLists(side, u, vm, ctx, outAdj, inAdj, adjSlice) {
  const N = vm.nodes, E = vm.layout.edges;
  const row = (ei, out) => {
    const e = E[ei];
    const o = N[out ? e.t : e.s];
    return `<div class="gref"><div class="gref-cls">${ctx.esc(o.short)}</div>` +
      `<div class="gref-meta"><span class="f">${out ? "holds" : "held by"}</span>` +
      `<span class="n">×${ctx.esc(ctx.fmtN(e.mult))}${N[e.s].edgesPerMember ? " per instance" : ""} · ${ctx.esc(ctx.fmtN(e.total))} total</span></div></div>`;
  };
  const queue = [];
  for (const [title, adj, out] of [["outbound references", outAdj, true],
                                   ["inbound references", inAdj, false]]) {
    const list = adjSlice(adj, u);
    if (!list.length) continue;
    const lab = document.createElement("div");
    lab.className = "glab";
    lab.textContent = `${title} — ${list.length.toLocaleString("en-US")}`;
    const box = document.createElement("div");
    box.className = "grefs";
    side.appendChild(lab);
    side.appendChild(box);
    queue.push({ box, list, out, pos: 0 });
  }
  const BATCH = 300;
  const pump = () => {
    let budget = BATCH;
    while (budget > 0 && queue.length) {
      const q = queue[0];
      const chunk = [];
      while (budget > 0 && q.pos < q.list.length) {
        chunk.push(row(q.list[q.pos++], q.out));
        budget--;
      }
      q.box.insertAdjacentHTML("beforeend", chunk.join(""));
      if (q.pos >= q.list.length) queue.shift();
    }
    if (!queue.length) side.removeEventListener("scroll", onScroll);
  };
  const onScroll = () => {
    if (side.scrollTop + side.clientHeight > side.scrollHeight - 600) pump();
  };
  side.addEventListener("scroll", onScroll);
  pump();
  return () => side.removeEventListener("scroll", onScroll);
}
