"use strict";
/* ============================== classes tab ============================== */
let clsTimer;
document.getElementById("cls-filter").addEventListener("input", e=>{
  clearTimeout(clsTimer);
  clsTimer = setTimeout(()=>{ CLS.filter = e.target.value.trim().toLowerCase(); CLS.page=0; loadClasses(); }, 180);
});
document.getElementById("cls-sort").onchange = e=>{ CLS.sort = e.target.value; CLS.page=0; loadClasses(); };
document.getElementById("cls-prev").onclick = ()=>{ if(CLS.page>0){CLS.page--; loadClasses();} };
document.getElementById("cls-next").onclick = ()=>{ if(CLS.page<CLS.pages-1){CLS.page++; loadClasses();} };

async function loadClasses(){
  if(API){
    const r = await jget(`/api/${dump}/classes?filter=${encodeURIComponent(CLS.filter)}&sort=${CLS.sort}&page=${CLS.page}`);
    CLS.rows = r.rows; CLS.total = r.total; CLS.pages = r.pages;
  } else {
    let rows = ICLS;
    if(CLS.filter) rows = rows.filter(r=>r.disp.toLowerCase().includes(CLS.filter));
    const k = CLS.sort.replace("-","");
    const key = r => k==="name"?r.disp.toLowerCase():(k==="r"?-(r.r??-1):-r[k]);
    rows = [...rows].sort((a,b)=>{ const x=key(a),y=key(b); return x<y?-1:x>y?1:0; });
    if(k==="r") rows.sort((a,b)=>(a.r==null)-(b.r==null) || (b.r-a.r));
    CLS.total = rows.length; CLS.pages = Math.max(1, Math.ceil(rows.length/200));
    CLS.rows = rows.slice(CLS.page*200, CLS.page*200+200);
  }
  renderClasses();
}

function renderClasses(){
  const rows = CLS.rows;
  let html = `<tr><th>class</th><th>objects</th><th>shallow</th><th>per inst</th><th>retained</th><th>per inst</th><th>analysis</th><th></th></tr>`;
  for(const r of rows){
    const cat = catOfName(r.disp);
    const badges = (r.comp?`<span class="bdg on" title="retained-set composition available">rs</span>`:"") +
                   r.anat.map(k=>`<span class="bdg on" title="anatomy extracted with ${k} samples">a${k}</span>`).join("");
    const lam = r.lams ? `<span class="lamexp" data-l="${esc(r.disp)}">▸ ${r.lams.length}</span>` : "";
    html += `<tr>
      <td class="cname" data-n="${esc(r.disp)}"><span class="catdot" style="background:${catColor(cat)}"></span><span title="${esc(r.pkg)}">${esc(r.name)}</span>${lam}</td>
      <td class="num">${fmtN(r.c)}</td><td class="num">${fmtB(r.s)}</td><td class="num">${fmtB(r.pi)}</td>
      <td class="num">${r.r!=null?fmtB(r.r):"—"}</td><td class="num">${r.r!=null?fmtB(r.r/Math.max(1,r.c)):"—"}</td>
      <td>${badges}</td>
      <td>${API&&r.analyzable?`<button class="an-btn" data-n="${esc(r.disp)}">Analyze</button>`:""}</td></tr>`;
    if(r.lams) html += `<tr class="lamrow" data-lrow="${esc(r.disp)}" style="display:none"><td colspan="8">${
        r.lams.map(l=>`<div>${esc(l[0])} — ${fmtN(l[1])} objs · ${fmtB(l[2])}</div>`).join("")}</td></tr>`;
  }
  document.getElementById("cls-table").innerHTML = html;
  document.getElementById("cls-count").textContent = `${fmtN(CLS.total)} classes`;
  document.getElementById("cls-page").textContent = `${CLS.page+1}/${CLS.pages}`;
  document.getElementById("cls-table").querySelectorAll("td.cname").forEach(td=>{
    const disp = td.dataset.n;
    const row = rows.find(r=>r.disp===disp);
    td.onclick = e=>{
      if(e.target.classList.contains("lamexp")) return;
      openModal(disp, {c:row.c, s:row.s, r:row.r});
    };
  });
  document.getElementById("cls-table").querySelectorAll(".lamexp").forEach(x=>x.onclick=()=>{
    const tr = document.querySelector(`tr.lamrow[data-lrow="${CSS.escape(x.dataset.l)}"]`);
    if(tr) tr.style.display = tr.style.display==="none" ? "" : "none";
  });
  document.getElementById("cls-table").querySelectorAll(".an-btn").forEach(b=>b.onclick=()=>{
    const row = rows.find(r=>r.disp===b.dataset.n);
    openModal(b.dataset.n, {c:row.c, s:row.s, r:row.r, tab:"analyze"});
  });
}
