"use strict";
/* ============================== modal: class detail shell, composition, analyze, jobs ============================== */
const backdrop = document.getElementById("backdrop");
function closeModal(){ backdrop.classList.remove("open"); }
document.getElementById("m-x").onclick = closeModal;
backdrop.addEventListener("click", e=>{ if(e.target===backdrop) closeModal(); });
addEventListener("keydown", e=>{ if(e.key==="Escape") closeModal(); });
document.getElementById("m-tabs").querySelectorAll("button").forEach(b=>b.onclick=()=>{
  M.tab = b.dataset.t;
  document.getElementById("m-tabs").querySelectorAll("button").forEach(x=>x.classList.toggle("on",x===b));
  if(M.tab==="tree2"||M.tab==="graph"||M.tab==="hier") ensureAnat2();
  paintModal();
});

async function openModal(full, info){
  const ce = CC.get(full) || {};
  HSTATE.path = [];
  M = {full, objCount:info.c||0, shallow:info.s||0, r:info.r??null,
       comp:ce.comp ?? null, anat:ce.anat ?? null, anat2:ce.anat2 ?? null,
       tab:info.tab||"flat", scale:"global", samples:null,
       pend:{comp:API&&!("comp" in ce), anat:API&&!("anat" in ce), anat2:false}};
  document.getElementById("m-tabs").querySelectorAll("button").forEach(x=>x.classList.toggle("on",x.dataset.t===M.tab));
  document.getElementById("m-title").textContent = full;
  paintModalHead(); paintModal();
  backdrop.classList.add("open");
  if(API){
    const jobs = [];
    if(M.pend.comp) jobs.push(["comp", jget(`/api/${dump}/composition/${encodeURIComponent(full)}`)]);
    if(M.pend.anat) jobs.push(["anat", jget(`/api/${dump}/anatomy/${encodeURIComponent(full)}`)]);
    if(!jobs.length) return;
    const res = await Promise.all(jobs.map(j=>j[1]));
    jobs.forEach((j,i)=>{ ce[j[0]] = res[i]; });
    CC.set(full, ce);
    if(M.full!==full) return;   // user moved on
    M.comp = ce.comp ?? null; M.anat = ce.anat ?? null;
    M.pend.comp = M.pend.anat = false;
    paintModalHead(); paintModal();
  } else {
    M.comp = INLINE.comps[full] || null;
    M.anat = INLINE.anats[full] || null;
    M.pend.comp = M.pend.anat = false;
    paintModalHead(); paintModal();
  }
}
async function ensureAnat2(){
  if(!API || !M.full) return;
  const full = M.full, ce = CC.get(full) || {};
  if("anat2" in ce){
    M.anat2 = ce.anat2; M.pend.anat2 = false;
    if(M.tab==="tree2"||M.tab==="graph"||M.tab==="hier") paintModal();
    return;
  }
  M.pend.anat2 = true;
  if(M.tab==="tree2"||M.tab==="graph"||M.tab==="hier") paintModal();
  const a = await jget(`/api/${dump}/anat2/${encodeURIComponent(full)}`);
  ce.anat2 = a; CC.set(full, ce);
  if(M.full!==full) return;   // user moved on
  M.anat2 = a; M.pend.anat2 = false;
  if(M.tab==="tree2"||M.tab==="graph"||M.tab==="hier") paintModal();
}
function paintModalHead(){
  const rs = M.comp;
  document.getElementById("m-sub").innerHTML =
    `${fmtN(M.objCount)} instances · ${fmtB(M.shallow)} shallow · avg ${fmtB(M.shallow/Math.max(1,M.objCount))}/instance`+
    (rs?`<br>retained set: ${fmtN(rs.totalObjects)} objects of ${rs.classes} classes, ${fmtB(rs.totalShallow)} shallow · avg ${fmtB(rs.totalShallow/Math.max(1,M.objCount))}/instance`
       :M.pend.comp?`<br><span class="spinner"></span>loading retained-set stats…`
       :M.r!=null?`<br>retained: ${fmtB(M.r)}`:"<br>retained set: not analyzed yet");
}

function flatHTML(){
  const c = M.comp;
  if(!c && M.pend.comp) return `<div class="loading"><span class="spinner"></span>loading retained-set composition…</div>`;
  if(!c) return `<div class="pad">No composition data for this class yet.<br><br>
    <button class="pri" id="go-analyze">Analyze it now</button> (MAT <code>show_retained_set</code> — about a minute on this machine)</div>`;
  const rows = c.rows, total = rows.reduce((a,r)=>a+r[1],0);
  const TOP = 18, top = rows.slice(0,TOP), rs2 = rows.slice(TOP);
  const restS = rs2.reduce((a,r)=>a+r[1],0), restC = rs2.reduce((a,r)=>a+r[2],0);
  const row = (r,other)=>{
    const pct = 100*r[1]/total;
    const cat = other?"other":catOfName(r[0]);
    return `<div class="crow${other?" other":""}">
      <div class="nm"${other?"":` title="${esc(r[0])}"`}><span class="dot" style="background:${catColor(cat)}"></span>${esc(r[0])}</div>
      <div class="track" title="${pct.toFixed(1)}% of retained set"><div class="bar" style="width:${Math.max(0.5,pct).toFixed(1)}%;background:${catColor(cat)}"></div></div>
      <div class="num">${fmtN(r[2])}</div><div class="num">${fmtB(r[1])}</div><div class="pct">${pct.toFixed(1)}%</div>
      <div class="num" title="shallow bytes per dominator instance">${other?"":fmtB(r[1]/Math.max(1,M.objCount))}</div></div>`;
  };
  return `<div class="crow head"><div>class</div><div>share of retained set</div><div class="num">objects</div><div class="num">shallow</div><div class="pct">%</div><div class="num">per instance</div></div>`+
    top.map(r=>row(r,false)).join("")+
    (rs2.length?row([`· ${rs2.length} more classes`,restS,restC],true):"")+
    (rs2.length?`<details style="margin-top:10px;font-size:12px"><summary style="cursor:pointer;color:var(--link)">show all ${rows.length} classes</summary><div style="margin-top:6px">${rs2.map(r=>row(r,false)).join("")}</div></details>`:"");
}

function analyzeHTML(){
  if(!API) return `<div class="pad">This is a static snapshot. Start the server for on-demand analysis:<br><br><code>python3 heap-report/serve.py</code></div>`;
  const deepest = Math.max(32, ...(M.anat?M.anat.available:[32]));
  const running = [...activeJobs.values()].find(j=>j.cls===M.full && (j.status==="queued"||j.status==="running"));
  return `<div class="pad" style="max-width:640px">
    <p>Run MAT against <code>${esc(STATS.dump||"the dump")}</code> to extract this class'
    retained-set composition and (optionally) a reference-tree anatomy over evenly-spread sample instances.
    Results are cached as CSVs — re-running is instant.</p>
    <p style="margin:14px 0 6px">samples: <input type="number" id="an-samples" value="${deepest<64?32:deepest*2}" min="1" max="1024" style="width:80px">
    &nbsp;<label><input type="checkbox" id="an-anat" checked> include anatomy (slower, ~minutes for big retained sets)</label></p>
    <p><button class="pri" id="an-run" ${running?"disabled":""}>${running?"analysis "+running.status+"…":"Run analysis"}</button>
    <span class="hint">composition-only runs are ~1 min</span></p>
    <div id="an-progress"></div>
  </div>`;
}

function paintModal(){
  const body = document.getElementById("m-body");
  body.style.overflow = M.tab==="graph" ? "hidden" : "";
  body.innerHTML = M.tab==="flat" ? flatHTML() : M.tab==="tree" ? treeHTML()
    : M.tab==="tree2" ? tree2HTML() : M.tab==="hier" ? hierHTML()
    : M.tab==="graph" ? refgraphHTML() : analyzeHTML();
  document.getElementById("m-note").textContent =
    M.tab==="tree" && M.anat ? `×N = occurrences across the ${M.anat.samples} samples; "in k/K" = field non-null in k of the ${M.anat.samples} sampled instances (a field missing from some instances is normal — lazily created). "(shared)" = referenced but owned by someone else, so its bytes are not inside this class's retained set.`
    : M.tab==="tree2" && M.anat2 ? `v2 over the full object graph: complete outbounds ${M.anat2.fullEdges?"(edgesfull extracted — the old 48-slot array cap hides nothing here)":"NOT extracted — re-run the analysis, big arrays still hide children!"}, depth cap ${M.anat2.depth}, strings & synthetic fields (this$0, dimmed) traversed. ⇆ = inbound refs from inside the set (more refs than objects ⇒ shared within it).`
    : M.tab==="hier" ? "The Anatomy-v2 tree laid out as a hierarchy and sized by retained heap — start at the class, descend into what accounts for its memory. Sibling widths are retained shares; shared-within-set subtrees sit on their first path (⇆ in tooltip)."
    : M.tab==="graph" ? "class-level reference DAG over the same extraction: node size = retained, topsorted layers (holders above, held below), edge thickness = referenced bytes."
    : M.tab==="flat" ? "Composition of the collective retained set: shallow bytes grouped by class of the retained objects (MAT show_retained_set)."
    : "";
  document.getElementById("m-foot").innerHTML =
    M.tab==="analyze" ? "Jobs run serially on the server; watch progress bottom-right. Everything lands in <code>data/</code> as CSVs." : "";
  const ga = document.getElementById("go-analyze");
  if(ga) ga.onclick = ()=>{ document.querySelector('#m-tabs button[data-t="analyze"]').click(); };
  const run = document.getElementById("an-run");
  if(run) run.onclick = submitAnalysis;
  if(M.tab==="tree"||M.tab==="tree2"){
    body.querySelectorAll(".tgl:not(.leaf)").forEach(t=>t.onclick=()=>{
      const kids = t.closest(".arow").nextElementSibling;
      if(!kids) return;
      const open = kids.style.display!=="none";
      kids.style.display = open?"none":"block";
      t.textContent = open?"▸":"▾";
    });
    body.querySelectorAll(".anatseg button").forEach(b=>b.onclick=()=>{ M.scale=b.dataset.s; paintModal(); });
  }
  if(M.tab==="tree"){
    body.querySelectorAll(".sels").forEach(x=>x.onclick=async e=>{
      e.preventDefault();
      const k = +x.dataset.k;
      if(API){ M.anat = await jget(`/api/${dump}/anatomy/${encodeURIComponent(M.full)}?samples=${k}`);
        const ce = CC.get(M.full); if(ce) ce.anat = M.anat; paintModal(); }
    });
  }
  if(M.tab==="tree2"){
    body.querySelectorAll(".sels2").forEach(x=>x.onclick=async e=>{
      e.preventDefault();
      const k = +x.dataset.k;
      if(API){
        M.pend.anat2 = true; paintModal();
        M.anat2 = await jget(`/api/${dump}/anat2/${encodeURIComponent(M.full)}?samples=${k}`);
        const ce = CC.get(M.full); if(ce) ce.anat2 = M.anat2;
        M.pend.anat2 = false; paintModal();
      }
    });
  }
  if(M.tab==="graph"){
    initGraph();
    const gt = body.querySelector("#gtop");
    if(gt){
      gt.oninput = ()=>{ body.querySelector("#gtopv").textContent = gt.value; };
      gt.onchange = ()=>{ GSTATE.top = +gt.value; initGraph(); };
    }
    body.querySelectorAll("#gseg button").forEach(b=>b.onclick=()=>{ M.scale=b.dataset.s; paintModal(); });
  }
  if(M.tab==="hier"){
    initHier();
    body.querySelectorAll("#hierseg button").forEach(b=>b.onclick=()=>{
      HSTATE.metric = b.dataset.m;
      body.querySelectorAll("#hierseg button").forEach(x=>x.classList.toggle("on", x===b));
      initHier();
    });
  }
}

/* ============================== analysis jobs ============================== */
async function submitAnalysis(){
  const samples = +document.getElementById("an-samples").value || 32;
  const anatomy = document.getElementById("an-anat").checked;
  const r = await fetch(`/api/${dump}/analyze`, {method:"POST",
    headers:{"Content-Type":"application/json"},
    body: JSON.stringify({"class":M.full, samples, anatomy})});
  const job = await r.json();
  if(!r.ok){ document.getElementById("an-progress").innerHTML = `<div class="pad" style="color:#e8743b">${esc(job.error||"failed")}</div>`; return; }
  activeJobs.set(job.id, job);
  paintModal();
  pollJobs();
}
async function pollJobs(){
  if(!API) return;
  let anyActive = false;
  try{
    const jobs = await jget("/api/jobs");
    for(const j of jobs){
      const prev = activeJobs.get(j.id);
      activeJobs.set(j.id, j);
      if(j.status==="queued"||j.status==="running") anyActive = true;
      else if(prev && (prev.status==="queued"||prev.status==="running")) onJobDone(j);
    }
  }catch(e){ /* server down */ }
  renderJobs();
  if(REMOTE!==null && document.getElementById("tab-remote").classList.contains("on")) renderRemote();
  if(anyActive) setTimeout(pollJobs, 2500);
}
function onJobDone(job){
  if(job.kind==="download"){
    refreshDumps().then(()=>{ if(REMOTE!==null) loadRemote(); });
    return;
  }
  if(dump===job.dump) loadClasses();
  CC.delete(job.cls);
  if(M.full===job.cls && backdrop.classList.contains("open")){
    M.pend.comp = M.pend.anat = true;
    M.anat2 = null;
    paintModalHead(); paintModal();
    Promise.all([
      jget(`/api/${dump}/composition/${encodeURIComponent(M.full)}`),
      jget(`/api/${dump}/anatomy/${encodeURIComponent(M.full)}`),
    ]).then(([c,a])=>{
      CC.set(job.cls, {comp:c, anat:a});
      if(M.full!==job.cls) return;
      M.comp = c; M.anat = a; M.pend.comp = M.pend.anat = false;
      paintModalHead(); paintModal();
      if(M.tab==="tree2"||M.tab==="graph"||M.tab==="hier") ensureAnat2();
    });
  }
}
const dismissedJobs = new Set();
function renderJobs(){
  const box = document.getElementById("jobs");
  const now = Date.now()/1000;
  const show = [...activeJobs.values()].filter(j=>!dismissedJobs.has(j.id) &&
      (j.status==="queued"||j.status==="running" || (j.ended && now - j.ended < 90)));
  clearTimeout(renderJobs._t);
  if(!show.length){ box.style.display="none"; return; }
  box.style.display = "block";
  box.innerHTML = show.map(j=>{
    const dl = j.kind==="download";
    const prog = dl && j.progress && j.progress.total ?
      ` · ${j.progress.stage} ${fmtB(j.progress.bytes)}/${fmtB(j.progress.total)}` : "";
    const detail = dl ? "" : ` · s${j.samples}${j.anatomy?" +anatomy":""}`;
    return `<div class="jcard">
    <button class="jclose" data-id="${j.id}" title="dismiss">×</button>
    <div class="jname">${esc(dl ? "⬇ "+j.dump : j.cls)}</div>
    <div class="jstatus">${j.status}${j.status==="failed"?" — see log":""}${detail} · ${dl?"":j.dump}${prog}</div>
    ${j.log&&j.log.length?`<pre>${esc(j.log.slice(-4).join("\n"))}</pre>`:""}
  </div>`;
  }).join("");
  box.querySelectorAll(".jclose").forEach(b=>b.onclick=()=>{ dismissedJobs.add(+b.dataset.id); renderJobs(); });
  // re-render when the oldest finished card's 90 s grace expires, so cards
  // disappear even after polling has stopped (nothing else re-renders then)
  const expiry = Math.min(...show.filter(j=>j.ended).map(j=>j.ended+90));
  if(isFinite(expiry)) renderJobs._t = setTimeout(renderJobs, Math.max(0,(expiry-now)*1000)+100);
}
