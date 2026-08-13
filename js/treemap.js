"use strict";
/* ============================== treemap ============================== */
const svg = document.getElementById("treemap");
const tvalue = n => n.leaf ? (n[TSTATE.metric]||0) : n.children.reduce((a,c)=>a+tvalue(c),0);
const tcount = n => n.leaf ? (n.c||0) : n.children.reduce((a,c)=>a+tcount(c),0);
document.getElementById("metricSeg").querySelectorAll("button").forEach(b=>b.onclick=()=>{
  TSTATE.metric = b.dataset.m; TSTATE.zoomPath = [];
  document.getElementById("metricSeg").querySelectorAll("button").forEach(x=>x.classList.toggle("on",x===b));
  renderTreemap(); renderLegend();
});

function squarify(children, x, y, w, h){
  const out = [];
  const items = children.map(c=>({n:c, v:tvalue(c)})).filter(d=>d.v>0).sort((a,b)=>b.v-a.v);
  const total = items.reduce((a,d)=>a+d.v,0);
  if(total<=0||w<=0||h<=0) return out;
  let scale = w*h/total, row = [], rowSum = 0, cx=x, cy=y, cw=w, ch=h;
  const worst = (sum,len)=>{
    let mx=Math.max(...row.map(d=>d.v)), mn=Math.min(...row.map(d=>d.v));
    return Math.max((len*len*mx)/(sum*sum), (sum*sum)/(len*len*mn));
  };
  const layoutRow = ()=>{
    const vert = cw>=ch, len = vert?ch:cw, thick = rowSum*scale/len;
    let off = vert?cy:cx;
    for(const d of row){
      const l = d.v*scale/thick;
      out.push(vert?{n:d.n,v:d.v,x:cx,y:off,w:thick,h:l}:{n:d.n,v:d.v,x:off,y:cy,w:l,h:thick});
      off += l;
    }
    if(vert){cx+=thick;cw-=thick;}else{cy+=thick;ch-=thick;}
    row=[];rowSum=0;
  };
  for(const d of items){
    const len = Math.min(cw,ch);
    if(row.length && worst(rowSum, len) < worst(rowSum+d.v, len)) layoutRow();
    row.push(d); rowSum+=d.v;
  }
  if(row.length) layoutRow();
  return out;
}
const shadeCache = {};
function shade(hex, seed){
  const key = hex+seed; if(shadeCache[key]) return shadeCache[key];
  let h=0; for(let i=0;i<seed.length;i++) h=(h*31+seed.charCodeAt(i))>>>0;
  const f = 0.82 + (h%100)/100*0.36;
  const c = hex.match(/\w\w/g).map(v=>Math.min(255,Math.round(parseInt(v,16)*f)));
  return shadeCache[key] = `rgb(${c[0]},${c[1]},${c[2]})`;
}
function treeRoot(){
  let base = TSTATE.metric==="r" ? TREES.dom : TREES.hist;
  let n = base;
  for(const name of TSTATE.zoomPath){
    const k = (n.children||[]).find(c=>c.name===name);
    if(!k){ TSTATE.zoomPath=[]; return base; }
    n = k;
  }
  return n;
}
function renderTreemap(){
  if(!TREES) return;
  const root = treeRoot();
  const W = svg.clientWidth||svg.parentElement.clientWidth||900, H = 620;
  svg.setAttribute("viewBox",`0 0 ${W} ${H}`);
  svg.innerHTML = "";
  const kids = root.children||[];
  const total = kids.reduce((a,c)=>a+tvalue(c),0);
  for(const b of squarify(kids,0,0,W,H)){
    const node = b.n;
    const g = document.createElementNS(NS,"g");
    const r = document.createElementNS(NS,"rect");
    r.setAttribute("class","cell");
    r.setAttribute("x",b.x); r.setAttribute("y",b.y);
    r.setAttribute("width",Math.max(0,b.w-0.5)); r.setAttribute("height",Math.max(0,b.h-0.5));
    r.setAttribute("fill", shade(catColor(node.cat), node.name));
    r.setAttribute("rx",2);
    g.appendChild(r);
    if(b.w>46&&b.h>15){
      const t = document.createElementNS(NS,"text");
      t.setAttribute("x",b.x+5); t.setAttribute("y",b.y+14);
      t.setAttribute("font-size","11"); t.setAttribute("opacity",".92");
      const maxCh = Math.floor((b.w-10)/6.6);
      let label = node.name.split(".").pop();
      if(label.length>maxCh) label = label.slice(0,Math.max(1,maxCh-1))+"…";
      t.textContent = label;
      g.appendChild(t);
      if(b.h>30){
        const v = document.createElementNS(NS,"text");
        v.setAttribute("x",b.x+5); v.setAttribute("y",b.y+27);
        v.setAttribute("font-size","10"); v.setAttribute("opacity",".65");
        v.textContent = TSTATE.metric==="c" ? fmtN(tvalue(node)) : fmtB(tvalue(node));
        g.appendChild(v);
      }
    }
    g.addEventListener("mousemove",e=>showTip(e,node,total));
    g.addEventListener("mouseleave",()=>tip.style.display="none");
    if(!node.leaf) g.addEventListener("click",()=>{TSTATE.zoomPath.push(node.name);renderTreemap();});
    else { g.style.cursor="pointer"; g.addEventListener("click",()=>openModal(node.disp,{c:node.c,s:node.s,r:node.r??null})); }
    svg.appendChild(g);
  }
  const cb = document.getElementById("crumbs");
  const parts = [`<a data-i="0">all</a>`];
  TSTATE.zoomPath.forEach((p,i)=>parts.push(`<a data-i="${i+1}">${p}</a>`));
  cb.innerHTML = parts.join(" › ");
  cb.querySelectorAll("a").forEach(a=>a.onclick=()=>{TSTATE.zoomPath=TSTATE.zoomPath.slice(0,+a.dataset.i);renderTreemap();});
}
function showTip(e,node,total){
  const v = tvalue(node), cnt = tcount(node);
  const pct = (100*v/(TSTATE.metric==="r"?STATS.totalRetained:TSTATE.metric==="s"?STATS.totalShallow:STATS.totalObjects));
  tip.innerHTML = `<div class="t-name">${esc(node.disp||node.name)}</div><table>
    <tr><td>${TSTATE.metric==="c"?"instances":TSTATE.metric==="s"?"shallow heap":"retained heap"}</td><td class="v">${TSTATE.metric==="c"?fmtN(v):fmtB(v)}</td><td class="v">${pct.toFixed(2)}%</td></tr>
    ${node.leaf?`<tr><td>instances</td><td class="v">${fmtN(node.c||0)}</td><td class="v">${(node.c/STATS.modules).toFixed(1)}/module</td></tr>`:`<tr><td>instances</td><td class="v">${fmtN(cnt)}</td><td></td></tr>`}
    ${TSTATE.metric!=="c"?`<tr><td>per module</td><td class="v">${fmtB(v/STATS.modules)}</td><td></td></tr>`:""}
    ${node.leaf&&node.r?`<tr><td>per instance</td><td class="v">${fmtB(node.r/Math.max(1,node.c))}</td><td></td></tr>`:""}
  </table>${node.leaf?"<div style='color:#f0883e;margin-top:4px'>click — open class ▸</div>":"<div style='color:#8b94a3;margin-top:4px'>click to zoom</div>"}`;
  tip.style.display="block";
  const tw=tip.offsetWidth, th=tip.offsetHeight;
  tip.style.left = Math.min(e.clientX+14, innerWidth-tw-10)+"px";
  tip.style.top  = Math.min(e.clientY+14, innerHeight-th-10)+"px";
}
function renderLegend(){
  if(!TREES) return;
  const DATA = TSTATE.metric==="r" ? TREES.dom : TREES.hist;
  document.getElementById("legend").innerHTML = DATA.children.map(c=>{
    const v=tvalue(c), tot=TSTATE.metric==="r"?STATS.totalRetained:TSTATE.metric==="s"?STATS.totalShallow:STATS.totalObjects;
    return `<span data-cat="${c.name}"><i style="background:${catColor(c.cat)}"></i>${c.name} — ${TSTATE.metric==="c"?fmtN(v):fmtB(v)} (${(100*v/tot).toFixed(1)}%)</span>`;
  }).join("");
  document.getElementById("legend").querySelectorAll("span").forEach(s=>s.onclick=()=>{
    document.querySelector('#tabs button[data-t="treemap"]').click();
    TSTATE.zoomPath=[s.dataset.cat]; renderTreemap();
  });
}
