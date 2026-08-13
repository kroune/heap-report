"use strict";
/* ============================== retained hierarchy: the v2 anatomy tree as an
   icicle, width = retained share ============================== */
let HSTATE = {metric:"r", path:[]};   // zoom state (reset on each opened class)

function hierHTML(){
  const a = M.anat2;
  if(M.pend.anat2) return `<div class="loading"><span class="spinner"></span>loading hierarchy…</div>`;
  if(!a) return `<div class="pad">No anatomy extracted for this class yet — the hierarchy is built from the full anatomy extraction.<br><br>
    Open the <b>Analyze</b> tab and run with anatomy enabled.</div>`;
  const m = HSTATE.metric;
  const seg = `<div class="anatseg" id="hierseg">
      <button data-m="r"${m==="r"?' class="on"':""}>retained</button>
      <button data-m="s"${m==="s"?' class="on"':""}>shallow</button>
      <button data-m="n"${m==="n"?' class="on"':""}>objects</button>
    </div>`;
  return `<div style="display:flex;align-items:baseline;gap:16px">${seg}
      <span class="hint">the anatomy tree as a top-down hierarchy — width = share of parent · click = descend · breadcrumb = back up · double-click = open that class</span></div>
    <div class="hiercrumbs" id="hiercrumbs"></div>
    <svg id="hier"></svg>`;
}

function initHier(){
  const svg = document.getElementById("hier");
  if(!svg || !M.anat2) return;
  const a = M.anat2, m = HSTATE.metric;
  const val = n => m==="r" ? n.r : m==="s" ? n.s : n.n;
  const fmtV = v => m==="n" ? fmtN(v) : fmtB(v);
  // root augmented with the still-unreachable groups, so the full retained mass shows
  const kids0 = [...a.tree.kids];
  if(a.untracked && a.untracked.length)
    kids0.push({name:"(still unreachable — grouped by cause)", full:"",
      n:a.untracked.reduce((x,g)=>x+g.n,0), s:a.untracked.reduce((x,g)=>x+g.s,0),
      r:a.untracked.reduce((x,g)=>x+g.r,0), kids:a.untracked.map(g=>g.tree)});
  const rootFull = {...a.tree, kids:kids0};
  let zroot = rootFull;
  for(const nm of HSTATE.path){
    const k = (zroot.kids||[]).find(c=>c.name===nm);
    if(!k){ HSTATE.path=[]; zroot=rootFull; break; }
    zroot = k;
  }
  const cb = document.getElementById("hiercrumbs");
  const names = [rootFull.name, ...HSTATE.path];
  cb.innerHTML = names.map((nm,i)=>`<a data-i="${i}">${esc(nm.length>46?nm.slice(0,46)+"…":nm)}</a>`).join(" › ");
  cb.querySelectorAll("a").forEach(x=>x.onclick=()=>{ HSTATE.path = HSTATE.path.slice(0,+x.dataset.i); initHier(); });

  const W = svg.clientWidth || svg.parentElement.clientWidth || 1000;
  const ROWH = 22, CAP = 2400;
  const zv = Math.max(1, val(zroot));
  const cells = [];
  (function lay(n, x, w, depth){
    if(cells.length > CAP) return;
    cells.push({n, x, w, depth});
    const kids = (n.kids||[]).filter(k=>val(k)>0).sort((p,q)=>val(q)-val(p));
    const tot = kids.reduce((t,k)=>t+val(k),0);
    if(tot<=0) return;
    let cx = x;
    for(const k of kids){
      const kw = w*val(k)/tot;
      if(kw >= 0.7 && cells.length <= CAP) lay(k, cx, kw, depth+1);
      cx += kw;
    }
  })(zroot, 0, W, 0);
  const maxD = Math.max(...cells.map(c=>c.depth), 0);
  const H = (maxD+1)*ROWH + 8;
  svg.setAttribute("viewBox", `0 0 ${W} ${H}`);
  svg.style.height = Math.min(900, H)+"px";
  svg.innerHTML = "";
  for(const c of cells){
    const g = document.createElementNS(NS,"g");
    const r = document.createElementNS(NS,"rect");
    r.setAttribute("x",c.x); r.setAttribute("y",c.depth*ROWH);
    r.setAttribute("width",Math.max(0,c.w-0.6)); r.setAttribute("height",ROWH-1.5);
    r.setAttribute("fill", c.n.full==="(external)" ? "#3c4756" : shade(catColor(catOfName(c.n.full||"")), c.n.name));
    if(c.n.sk) r.setAttribute("opacity","0.55");
    r.setAttribute("rx",1);
    g.appendChild(r);
    if(c.w>46){
      const t = document.createElementNS(NS,"text");
      t.setAttribute("x",c.x+4); t.setAttribute("y",c.depth*ROWH+15);
      t.setAttribute("font-size","10.5"); t.setAttribute("fill","#fff"); t.setAttribute("opacity",".95");
      const maxCh = Math.floor((c.w-8)/6.4);
      let label = c.n.name;
      if(label.length>maxCh) label = label.slice(0,Math.max(1,maxCh-1))+"…";
      t.textContent = label;
      g.appendChild(t);
      if(c.w>150){
        const v = document.createElementNS(NS,"text");
        v.setAttribute("x",c.x+c.w-6); v.setAttribute("y",c.depth*ROWH+15);
        v.setAttribute("font-size","10"); v.setAttribute("fill","#fff"); v.setAttribute("opacity",".75");
        v.setAttribute("text-anchor","end");
        v.textContent = `${fmtV(val(c.n))} · ${(100*val(c.n)/zv).toFixed(1)}%`;
        g.appendChild(v);
      }
    }
    g.addEventListener("mousemove",e=>hierTip(e,c.n,zv));
    g.addEventListener("mouseleave",()=>tip.style.display="none");
    if((c.n.kids||[]).length)
      g.addEventListener("click",()=>{ HSTATE.path.push(c.n.name); initHier(); });
    if(c.n.full && !c.n.full.startsWith("("))
      g.addEventListener("dblclick",()=>openModal(c.n.full,{c:c.n.n,s:c.n.s}));
    svg.appendChild(g);
  }
}
function hierTip(e, n, zv){
  const m = HSTATE.metric;
  const v = m==="r" ? n.r : m==="s" ? n.s : n.n;
  tip.innerHTML = `<div class="t-name">${esc(n.name)}</div><table>
    <tr><td>retained</td><td class="v">${fmtB(n.r)}</td><td class="v">${(100*n.r/Math.max(1,M.anat2.tree.r)).toFixed(1)}% of class</td></tr>
    <tr><td>shallow</td><td class="v">${fmtB(n.s)}</td><td></td></tr>
    <tr><td>objects</td><td class="v">${fmtN(n.n)}</td><td></td></tr>
    <tr><td>in this view</td><td class="v">${(100*v/zv).toFixed(1)}%</td><td></td></tr>
    ${n.refs?`<tr><td>shared in set</td><td class="v">⇆ ${fmtN(n.refs)} refs</td><td></td></tr>`:""}
    ${n.pres!=null?`<tr><td>present in</td><td class="v">${n.pres}/${M.anat2.samples} samples</td><td></td></tr>`:""}
  </table><div style='color:#f0883e;margin-top:4px'>click — descend · double-click — open class</div>`;
  tip.style.display="block";
  const tw=tip.offsetWidth, th=tip.offsetHeight;
  tip.style.left = Math.min(e.clientX+14, innerWidth-tw-10)+"px";
  tip.style.top  = Math.min(e.clientY+14, innerHeight-th-10)+"px";
}
