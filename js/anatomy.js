"use strict";
/* ============================== anatomy views (v1 reference tree, v2 full tree) ============================== */

function treeHTML(){
  const a = M.anat;
  if(!a && M.pend.anat) return `<div class="loading"><span class="spinner"></span>loading reference-tree anatomy…</div>`;
  if(!a) return `<div class="pad">No anatomy extracted for this class yet.<br><br>
    Open the <b>Analyze</b> tab and run with anatomy enabled — a reference tree over evenly-spread sample instances (with per-field presence stats).</div>`;
  const t = a.tree, K = a.samples;
  const G = M.scale==="global";
  const Mf = Math.max(1, M.objCount)/K;
  const totRet = Math.max(1, M.comp?M.comp.totalShallow:t.r*Mf);
  const untracked = n => n.name==="(held via untracked/shared refs)";
  const shared = n => n.full==="(external)";
  const cellN = n => G?`<div class="num" title="estimated: per-instance average × ${fmtN(M.objCount)} instances">≈ ${fmtN(n.n*Mf)}</div>`:`<div class="num">${fmtN(n.n)}</div>`;
  const cellS = n => G?`<div class="num" title="estimated shallow">≈ ${fmtB(n.s*Mf)}</div>`:`<div class="num">${fmtB(n.s)}</div>`;
  const cellR = n => {
    if(!G) return `<div class="num">${fmtB(n.r)}</div>`;
    if(untracked(n)||shared(n)) return `<div class="num" style="color:var(--dim)" title="shared objects — not meaningful when extrapolated per instance">—</div>`;
    const v = n.r*Mf, pct = 100*v/totRet;
    return `<div class="num" title="estimated retained (${pct.toFixed(1)}% of class total)">≈ ${fmtB(v)} <span class="pct">${pct<0.05?"":pct.toFixed(0)+"%"}</span></div>`;
  };
  const pres = n => n.pres!=null?`<span class="pres" title="field non-null in ${n.pres} of ${K} sampled instances">in ${n.pres}/${K}</span>`:"";
  const arow = (n,depth)=>{
    const prim = n.full==="(field)";
    const cls = prim?"prim":(shared(n)?"shared":"");
    return `<div class="arow ${cls}"><div class="nm"><span class="tgl${n.kids&&n.kids.length?"":" leaf"}">${n.kids&&n.kids.length?"▸":"·"}</span><span class="adot" style="background:${catColor(catOfName(n.full||""))}"></span><span title="${esc(n.full||"")}">${esc(n.name)}</span>${pres(n)} <span class="acnt" title="${G?"average occurrences per instance":"occurrences across the "+K+" samples"}">×${G?fmtN(n.n/K):n.n}</span></div>${cellN(n)}${cellS(n)}<div class="num per">${fmtB(n.s/K)}</div>${cellR(n)}</div>`;
  };
  const rec = (n,depth)=>{
    let h = arow(n,depth);
    if(n.kids&&n.kids.length)
      h += `<div class="anode" style="display:${depth<1?"block":"none"}">${n.kids.map(k=>rec(k,depth+1)).join("")}</div>`;
    return h;
  };
  const seg = `<div class="anatseg">
      <button data-s="sample"${G?"":' class="on"'}>${K} sample instances</button>
      <button data-s="global"${G?' class="on"':""}>× ${fmtN(M.objCount)} instances (estimated)</button>
    </div>`;
  const avail = a.available && a.available.length>1
    ? `<span style="margin-left:14px;font-size:11px;color:var(--dim)">extractions: ${a.available.map(k=>`<a href="#" class="sels" data-k="${k}" style="color:${k===K?"var(--accent)":"var(--link)"}">s${k}</a>`).join(" · ")}</span>` : "";
  return `<div style="display:flex;align-items:baseline">${seg}${avail}</div>`+
    `<div class="arow head"><div>reference tree${G?" — extrapolated to all instances":""} (union retained set of ${K} samples)</div><div class="num">objects</div><div class="num">shallow</div><div class="num">per instance</div><div class="num">retained</div></div>` + rec(t,0);
}

function tree2HTML(){
  const a = M.anat2;
  if(M.pend.anat2) return `<div class="loading"><span class="spinner"></span>loading full-tree anatomy… (first build parses ~50 MB of extracts, then it is cached)</div>`;
  if(!a) return `<div class="pad">No anatomy extracted for this class yet.<br><br>
    Open the <b>Analyze</b> tab and run with anatomy enabled.</div>`;
  const t = a.tree, K = a.samples;
  const G = M.scale==="global";
  const Mf = Math.max(1, M.objCount)/K;
  const totRet = Math.max(1, M.comp?M.comp.totalShallow:t.r*Mf);
  const shared = n => n.full==="(external)";
  const cellN = n => G?`<div class="num" title="estimated: per-instance average × ${fmtN(M.objCount)} instances">≈ ${fmtN(n.n*Mf)}</div>`:`<div class="num">${fmtN(n.n)}</div>`;
  const cellS = n => G?`<div class="num" title="estimated shallow">≈ ${fmtB(n.s*Mf)}</div>`:`<div class="num">${fmtB(n.s)}</div>`;
  const cellR = n => {
    if(!G) return `<div class="num">${fmtB(n.r)}</div>`;
    if(shared(n)) return `<div class="num" style="color:var(--dim)" title="shared objects — not meaningful when extrapolated per instance">—</div>`;
    const v = n.r*Mf, pct = 100*v/totRet;
    return `<div class="num" title="estimated retained (${pct.toFixed(1)}% of class total)">≈ ${fmtB(v)} <span class="pct">${pct<0.05?"":pct.toFixed(0)+"%"}</span></div>`;
  };
  const pres = n => n.pres!=null?`<span class="pres" title="field non-null in ${n.pres} of ${K} sampled instances">in ${n.pres}/${K}</span>`:"";
  const refs = n => n.refs!=null?`<span class="refs" title="${fmtN(n.refs)} inbound references from inside the retained set onto ${fmtN(n.n)} object(s) — shared within the set (shown here on the first path only)">⇆${fmtN(n.refs)}</span>`:"";
  const arow = n => {
    const cls = n.full==="(field)"?"prim":(shared(n)?"shared":(n.sk?"sk":""));
    const skt = n.sk?` title="held via a synthetic field (${esc(n.name.split(":")[0])}) — hidden in the v1 tree"`:"";
    return `<div class="arow ${cls}"><div class="nm"${skt}><span class="tgl${n.kids&&n.kids.length?"":" leaf"}">${n.kids&&n.kids.length?"▸":"·"}</span><span class="adot" style="background:${catColor(catOfName(n.full||""))}"></span><span title="${esc(n.full||"")}">${esc(n.name)}</span>${pres(n)}${refs(n)} <span class="acnt" title="${G?"average occurrences per instance":"occurrences across the "+K+" samples"}">×${G?fmtN(n.n/K):n.n}</span></div>${cellN(n)}${cellS(n)}<div class="num per">${fmtB(n.s/K)}</div>${cellR(n)}</div>`;
  };
  const rec = (n,depth)=>{
    let h = arow(n);
    if(n.kids&&n.kids.length)
      h += `<div class="anode" style="display:${depth<1?"block":"none"}">${n.kids.map(k=>rec(k,depth+1)).join("")}</div>`;
    return h;
  };
  const seg = `<div class="anatseg">
      <button data-s="sample"${G?"":' class="on"'}>${K} sample instances</button>
      <button data-s="global"${G?' class="on"':""}>× ${fmtN(M.objCount)} instances (estimated)</button>
    </div>`;
  const avail = a.available && a.available.length>1
    ? `<span style="margin-left:14px;font-size:11px;color:var(--dim)">extractions: ${a.available.map(k=>`<a href="#" class="sels2" data-k="${k}" style="color:${k===K?"var(--accent)":"var(--link)"}">s${k}</a>`).join(" · ")}</span>` : "";
  const head = `<div class="arow head"><div>reference tree v2${G?" — extrapolated to all instances":""} (full graph over the union retained set of ${K} samples)</div><div class="num">objects</div><div class="num">shallow</div><div class="num">per instance</div><div class="num">retained</div></div>`;
  let uhtml = "";
  if(a.untracked && a.untracked.length){
    uhtml = `<h3 class="sec" style="margin:18px 0 4px">Still unreachable (${a.untracked.length} group${a.untracked.length>1?"s":""}) — with their structure, not flat</h3>` +
      a.untracked.map(g=>`<div class="ugrp"><b>${esc(g.tree.name)}</b> — ${fmtN(g.n)} objects · ${G?"≈ "+fmtB(g.s*Mf):fmtB(g.s)} shallow · ${G?"≈ "+fmtB(g.r*Mf):fmtB(g.r)} retained</div>`+
        `<div class="anode" style="display:block">${g.tree.kids.map(k=>rec(k,1)).join("")}</div>`).join("");
  } else {
    uhtml = `<div class="ugrp">Every object in the retained set is reachable in the tree above — no untracked remainder.${a.fullEdges?"":" (Warning: no edgesfull extraction found — re-run the analysis, big arrays may still be hiding children.)"}</div>`;
  }
  return `<div style="display:flex;align-items:baseline">${seg}${avail}</div>` + head + rec(t,0) + uhtml;
}
