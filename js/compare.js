"use strict";
/* ============================== compare tab ============================== */
document.getElementById("cmp-run").onclick = runCompare;
let CMP = null, CMPSORT = "abs";
const fmtS = v => (v>0?"+":v<0?"−":"")+fmtB(Math.abs(v));
const short = n => n.split(".").pop();
async function runCompare(){
  const o = document.getElementById("cmp-old").value, n = document.getElementById("cmp-new").value;
  document.getElementById("cmp-out").innerHTML = `<div class="pad">comparing ${esc(o)} → ${esc(n)} …</div>`;
  CMP = await jget(`/api/compare?old=${encodeURIComponent(o)}&new=${encodeURIComponent(n)}`);
  CMP.oldName = o; CMP.newName = n;
  CMPSORT = "abs";
  paintCompare();
}
/* --- waterfall: freed vs absorbed --- */
function wfSide(title, rows, rest, restN, sum, mx, color){
  const bar = v=>`<div class="track"><div class="bar" style="width:${(100*Math.abs(v)/mx).toFixed(1)}%;background:${color}"></div></div>`;
  let h = `<div><h4>${title} — ${fmtB(Math.abs(sum))}</h4>`;
  for(const [name,v] of rows)
    h += `<div class="wfrow"><div class="nm" title="${esc(name)}">${esc(short(name))}</div>${bar(v)}<div class="num ${v<0?"neg":"pos"}">${fmtS(v)}</div></div>`;
  if(restN>0)
    h += `<div class="wfrow"><div class="nm" style="color:var(--dim);font-style:italic">· ${fmtN(restN)} more classes (long tail)</div>${bar(rest)}<div class="num ${rest<0?"neg":"pos"}">${fmtS(rest)}</div></div>`;
  h += `<div class="wfrow total"><div>Σ ${title.toLowerCase()}</div>${bar(sum)}<div class="num">${fmtS(sum)}</div></div></div>`;
  return h;
}
function waterfallHTML(wf){
  const mx = Math.max(-wf.freedSum, wf.absorbedSum, 1);
  return `<h3 class="sec">Freed → absorbed <span style="font-weight:400;text-transform:none">(both heaps at the OOM ceiling: every freed byte is re-absorbed by something — the two sides must balance)</span></h3>
  <div class="wf">
    ${wfSide("Freed", wf.freed, wf.freedRest, wf.freedRestN, wf.freedSum, mx, "var(--c-agp)")}
    ${wfSide("Absorbed", wf.absorbed, wf.absorbedRest, wf.absorbedRestN, wf.absorbedSum, mx, "var(--c-gradle)")}
  </div>
  <div class="hint" style="margin-top:4px">Σ freed ${fmtB(-wf.freedSum)} vs Σ absorbed ${fmtB(wf.absorbedSum)} — net ${fmtS(wf.freedSum+wf.absorbedSum)} (≈ how far the ceiling itself moved). Long-tail sums shown explicitly: many small movers can outweigh the top rows.</div>`;
}
/* --- dominator deltas --- */
function domHTML(dom){
  return `<h3 class="sec">Dominator deltas <span style="font-weight:400;text-transform:none">(top-level dominators only — owned memory, closest to the cause; MAT rolls dominated objects into their owner)</span></h3>
  <table class="cmp"><tr><th>class</th><th>old objs</th><th>new objs</th><th>old retained</th><th>new retained</th><th>Δ retained</th><th>Δ shallow</th></tr>
  ${dom.slice(0,200).map(r=>{
    const ds = r[4]-r[3], dr = r[7];
    return `<tr><td class="cname" title="${esc(r[0])}">${esc(short(r[0]))}</td>
    <td class="num">${fmtN(r[1])}</td><td class="num">${fmtN(r[2])}</td>
    <td class="num">${fmtB(r[5])}</td><td class="num">${fmtB(r[6])}</td>
    <td class="num ${dr<0?"neg":dr>0?"pos":""}">${fmtS(dr)}</td>
    <td class="num ${ds<0?"neg":ds>0?"pos":""}">${fmtS(ds)}</td></tr>`;}).join("")}</table>
  <div class="hint" style="margin-top:6px">top 200 by |Δ retained| · ${fmtN(dom.length)} dominator classes total</div>`;
}
/* --- anatomy diffs --- */
function anatDiffHTML(anats){
  const keys = Object.keys(anats);
  if(!keys.length) return "";
  let h = `<h3 class="sec">Anatomy diffs <span style="font-weight:400;text-transform:none">(per sampled instance, field-path matched — what changed inside a typical instance; instance-count changes are in the class deltas)</span></h3>`;
  for(const full of keys){
    const d = anats[full];
    const rows = d.rows.map(r=>{
      const parts = r[0].split("/");
      const depth = Math.min(parts.length-2, 10);
      return `<tr><td class="cname" style="padding-left:${8+depth*14}px" title="${esc(r[0])}">${esc(parts.pop())}</td>
      <td class="num">${fmtB(r[1])}</td><td class="num">${fmtB(r[2])}</td>
      <td class="num ${r[3]<0?"neg":r[3]>0?"pos":""}">${fmtS(r[3])}</td>
      <td class="num">${fmtB(r[4])}</td><td class="num">${fmtB(r[5])}</td>
      <td class="num ${r[6]<0?"neg":r[6]>0?"pos":""}">${fmtS(r[6])}</td></tr>`;}).join("");
    h += `<details class="cmpd"><summary>${esc(short(full))} — ${fmtN(d.total)} changed paths · samples s${d.samples[0]} → s${d.samples[1]}</summary>
      <table class="cmp"><tr><th>field path (per-instance values)</th><th>old shallow</th><th>new shallow</th><th>Δ shallow</th><th>old retained</th><th>new retained</th><th>Δ retained</th></tr>
      ${rows}</table>
      <div class="hint" style="margin-top:4px">${d.total>d.rows.length?`top ${d.rows.length} of ${fmtN(d.total)} changed paths by |Δ| · `:""}string-value labels differ per run → same-content strings show as remove+add pairs; "(held via untracked/shared refs)" is memory owned by others that leaked into the sample</div></details>`;
  }
  return h;
}
/* --- rs drill-down: analyze in both dumps if needed, then diff compositions --- */
async function drillRs(btn, full){
  const tr = btn.closest("tr");
  if(tr.nextElementSibling && tr.nextElementSibling.classList.contains("drill")){
    tr.nextElementSibling.remove(); return;
  }
  const cell = document.createElement("tr");
  cell.className = "drill";
  cell.innerHTML = `<td colspan="8" style="padding:10px 14px">…</td>`;
  tr.after(cell);
  const td = cell.firstChild;
  try{
    for(const side of ["old","new"]){
      if(CMP.analyzed[side].includes(full)) continue;
      const dumpName = side==="old"?CMP.oldName:CMP.newName;
      td.textContent = `queuing retained-set analysis of ${short(full)} in ${dumpName} (composition-only, ~1 min; progress bottom-right)…`;
      const r = await fetch(`/api/${dumpName}/analyze`, {method:"POST",
        headers:{"Content-Type":"application/json"},
        body: JSON.stringify({"class":full, samples:8, anatomy:false})});
      const job = await r.json();
      if(!r.ok) throw new Error(job.error||"analyze failed");
      activeJobs.set(job.id, job); renderJobs(); pollJobs();
    }
    const t0 = Date.now();
    let co = null, cn = null;
    while(Date.now()-t0 < 15*60*1000){
      [co, cn] = await Promise.all([
        jget(`/api/${CMP.oldName}/composition/${encodeURIComponent(full)}`),
        jget(`/api/${CMP.newName}/composition/${encodeURIComponent(full)}`)]);
      if(co && cn) break;
      td.textContent = `waiting for retained-set analysis (${co?"✓":"…"} old / ${cn?"✓":"…"} new)…`;
      await new Promise(res=>setTimeout(res, 4000));
    }
    if(!co || !cn){ td.textContent = "timed out waiting for analysis — check the jobs panel."; return; }
    for(const side of ["old","new"]) if(!CMP.analyzed[side].includes(full)) CMP.analyzed[side].push(full);
    td.innerHTML = rsDiffHTML(full, co, cn);
  }catch(e){ td.textContent = "drill failed: "+e.message; }
}
function rsDiffHTML(full, co, cn){
  const LAM = /\$\$Lambda\+0x[0-9a-f]+$/;   // per-run address — merge specializations like the histogram does
  const m = new Map();
  const put = (r, i) => {
    const k = r[0].replace(LAM, "$$Lambda*");
    const e = m.get(k) || [0, 0, 0, 0];
    e[i] += r[1]; e[i+2] += r[2];
    m.set(k, e);
  };
  for(const r of co.rows) put(r, 0);
  for(const r of cn.rows) put(r, 1);
  const rows = [...m.entries()].map(([k,e])=>[k, e[0], e[1], e[1]-e[0], e[2], e[3]])
    .sort((a,b)=>Math.abs(b[3])-Math.abs(a[3]));
  const dt = cn.totalShallow - co.totalShallow;
  return `<div style="padding:2px 0 4px">
    <div style="margin-bottom:6px">retained set of <b title="${esc(full)}">${esc(short(full))}</b>:
      ${fmtB(co.totalShallow)} → ${fmtB(cn.totalShallow)}
      <b class="${dt<0?"neg":dt>0?"pos":""}">${fmtS(dt)}</b>
      · ${fmtN(co.totalObjects)} → ${fmtN(cn.totalObjects)} objects</div>
    <table class="cmp"><tr><th>inside the retained set</th><th>old shallow</th><th>new shallow</th><th>Δ shallow</th><th>old objs</th><th>new objs</th></tr>
    ${rows.slice(0,30).map(r=>`<tr><td class="cname" title="${esc(r[0])}">${esc(short(r[0]))}</td>
      <td class="num">${fmtB(r[1])}</td><td class="num">${fmtB(r[2])}</td>
      <td class="num ${r[3]<0?"neg":r[3]>0?"pos":""}">${fmtS(r[3])}</td>
      <td class="num">${fmtN(r[4])}</td><td class="num">${fmtN(r[5])}</td></tr>`).join("")}</table>
    <div class="hint" style="margin-top:4px">top 30 inner movers of ${fmtN(rows.length)} classes seen in either retained set (each side lists its top 100 by shallow; the totals above are complete)</div>
  </div>`;
}
function rsBtn(full){
  return API && !full.endsWith("$$Lambda*") ? `<button class="rsbtn" data-rs="${esc(full)}" title="diff the retained-set composition of this class between the two dumps (runs analysis where missing)">rs diff</button>` : "";
}
function cmpDeltaRow(r){
  const dc = r[3], ds = r[6];
  const cls = ds<0?"neg":ds>0?"pos":"";
  return `<tr><td class="cname" title="${esc(r[0])}">${esc(short(r[0]))}${rsBtn(r[0])}</td>
    <td class="num">${fmtN(r[1])}</td><td class="num">${fmtN(r[2])}</td>
    <td class="num ${dc<0?"neg":dc>0?"pos":""}">${dc>0?"+":""}${fmtN(dc)}</td>
    <td class="num">${fmtB(r[4])}</td><td class="num">${fmtB(r[5])}</td>
    <td class="num ${cls}">${fmtS(ds)}</td></tr>`;
}
function paintCompare(){
  const c = CMP;
  if(!c) return;
  const dS = c.new.totalShallow - c.old.totalShallow;
  const dO = c.new.totalObjects - c.old.totalObjects;
  let html = `<div class="stats" style="padding:10px 0 0">
    <div class="stat"><b>${fmtB(c.old.totalShallow)} → ${fmtB(c.new.totalShallow)}</b><span>reachable heap (both OOM ⇒ both at ceiling)</span><em>Δ ${fmtS(dS)} — meaningless at the ceiling, see freed → absorbed below</em></div>
    <div class="stat"><b>${fmtN(c.old.totalObjects)} → ${fmtN(c.new.totalObjects)}</b><span>live objects</span><em>Δ ${fmtS(dO)}</em></div>
  </div>`;
  html += waterfallHTML(c.waterfall);
  html += `<h3 class="sec">Build progress proxies <span style="font-weight:400;text-transform:none">(did the new run get further before OOM?)</span></h3>
    <table class="cmp"><tr><th>class</th><th>old objects</th><th>new objects</th><th>Δ</th><th>old shallow</th><th>new shallow</th></tr>
    ${c.proxies.map(p=>{
      const dc = p[2]-p[1];
      return `<tr><td class="cname" title="${esc(p[0])}">${esc(short(p[0]))}</td>
        <td class="num">${fmtN(p[1])}</td><td class="num">${fmtN(p[2])}</td>
        <td class="num ${dc<0?"neg":dc>0?"pos":""}">${dc>0?"+":""}${fmtN(dc)}</td>
        <td class="num">${fmtB(p[3])}</td><td class="num">${fmtB(p[4])}</td></tr>`;}).join("")}</table>`;
  html += domHTML(c.dom);
  if(c.retained.length){
    html += `<h3 class="sec">Retained-set deltas <span style="font-weight:400;text-transform:none">(classes analyzed in both dumps — the trustworthy comparison)</span></h3>
      <table class="cmp"><tr><th>class</th><th>old retained set</th><th>new retained set</th><th>Δ</th></tr>
      ${c.retained.map(r=>`<tr><td class="cname" title="${esc(r[0])}">${esc(short(r[0]))}${rsBtn(r[0])}</td>
        <td class="num">${fmtB(r[1])}</td><td class="num">${fmtB(r[2])}</td>
        <td class="num ${r[3]<0?"neg":r[3]>0?"pos":""}">${fmtS(r[3])}</td></tr>`).join("")}</table>`;
  }
  html += anatDiffHTML(c.anats);
  const rows = c.rows;
  const sortFns = {abs:(a,b)=>Math.abs(b[6])-Math.abs(a[6]), down:(a,b)=>a[6]-b[6], up:(a,b)=>b[6]-a[6],
                   cdown:(a,b)=>a[3]-b[3], cup:(a,b)=>b[3]-a[3]};
  const sorted = [...rows].sort(sortFns[CMPSORT]||sortFns.abs);
  html += `<h3 class="sec">Class deltas — raw histogram (lambda-normalized)
      <span style="font-weight:400;text-transform:none;margin-left:10px">
      sort: ${[["abs","|Δ shallow|"],["down","biggest decrease"],["up","biggest increase"],["cdown","count decrease"],["cup","count increase"]]
        .map(([k,l])=>`<a href="#" data-cs="${k}" style="color:${CMPSORT===k?"var(--accent)":"var(--link)"}">${l}</a>`).join(" · ")}</span></h3>
    <table class="cmp"><tr><th>class</th><th>old objs</th><th>new objs</th><th>Δ objs</th><th>old shallow</th><th>new shallow</th><th>Δ shallow</th></tr>
    ${sorted.slice(0,400).map(cmpDeltaRow).join("")}</table>
    <div class="hint" style="margin-top:6px">top 400 by current sort · ${fmtN(rows.length)} classes total · this table is zero-sum noise at the ceiling — the sections above explain it</div>`;
  document.getElementById("cmp-out").innerHTML = html;
  document.getElementById("cmp-out").querySelectorAll("[data-cs]").forEach(a=>a.onclick=e=>{
    e.preventDefault(); CMPSORT = a.dataset.cs; paintCompare();
  });
  document.getElementById("cmp-out").querySelectorAll(".rsbtn").forEach(b=>b.onclick=e=>{
    e.stopPropagation(); drillRs(b, b.dataset.rs);
  });
}
