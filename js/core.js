"use strict";
/* ============================== helpers ============================== */
const CATS = [
  {id:"gradle", name:"Gradle core",   color:"#e8743b"},
  {id:"agp",    name:"Android (AGP)", color:"#3ba272"},
  {id:"kotlin", name:"Kotlin plugin", color:"#9b7ede"},
  {id:"jdk",    name:"JDK / collections", color:"#4a90d9"},
  {id:"other",  name:"Other",         color:"#7d8590"},
];
const catColor = id => (CATS.find(c=>c.id===id)||{}).color || "#7d8590";
const catOfName = n => n.startsWith("org.gradle")?"gradle":n.startsWith("com.android")?"agp":
     n.startsWith("org.jetbrains.kotlin")?"kotlin":/^java|^jdk|^sun|^com\.sun/.test(n)?"jdk":"other";
const fmtB = v => v>=1e9?(v/1e9).toFixed(2)+" GB":v>=1e6?(v/1e6).toFixed(1)+" MB":v>=1e3?(v/1e3).toFixed(1)+" KB":(v>=100?Math.round(v):v.toFixed(1))+" B";
const fmtN = v => v>=1e6?(v/1e6).toFixed(2)+"M":v>=1e3?(v/1e3).toFixed(1)+"k":""+v;
const fmtD = v => (v>0?"+":"")+fmtB(Math.abs(v)).replace(/^/,"") ;
const esc = s => s.replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/"/g,"&quot;");
const jget = async u => { const r = await fetch(u); if(r.status===404) return null;
  if(!r.ok){ const t = await r.text(); let m = t; try{ m = JSON.parse(t).error || t; }catch{} throw new Error(m); }
  return r.json(); };
const NS = "http://www.w3.org/2000/svg";
const tip = document.getElementById("tip");

/* ============================== state ============================== */
let DUMPS = [], dump = null, STATS = null, TREES = null;
let CLS = {filter:"", sort:"-s", page:0, pages:1, total:0, rows:[]};
let ICLS = null;   // static mode: decoded full class array
let TSTATE = {metric:"r", zoomPath:[], filter:""};
let M = {full:null, objCount:0, shallow:0, r:null, comp:null, anat:null, anat2:null, tab:"flat", scale:"global", samples:null,
         pend:{comp:false, anat:false, anat2:false}};
const CC = new Map();   // per-class payload cache: full -> {comp, anat, anat2} (missing key = not fetched yet, null = 404)
let activeJobs = new Map();

/* ============================== boot ============================== */
async function refreshDumps(){
  DUMPS = await jget("/api/dumps");
  const ready = DUMPS.filter(d=>!d.incomplete);
  const sel = document.getElementById("dumpsel");
  sel.style.display = ready.length>1 ? "" : "none";
  sel.innerHTML = ready.map(d=>`<option>${d.name}</option>`).join("");
  sel.value = dump || "";
  const os = document.getElementById("cmp-old"), ns = document.getElementById("cmp-new");
  os.innerHTML = ns.innerHTML = ready.map(d=>`<option>${d.name}</option>`).join("");
  if(ready.length>1){ os.value = ready[0].name; ns.value = ready[1].name; }
}

async function boot(){
  if(API){
    await refreshDumps();
    const ready = DUMPS.filter(d=>!d.incomplete);
    if(!ready.length){
      document.getElementById("hsub").textContent = DUMPS.length
        ? "local dumps are incomplete (interrupted download/bootstrap) — resume them from the Remote tab"
        : "no local dumps yet — grab one from the Remote tab";
      document.querySelector('#tabs button[data-t="remote"]').click();
      pollJobs();
      return;
    }
    const sel = document.getElementById("dumpsel");
    const want = new URLSearchParams(location.search).get("dump");
    dump = ready.find(d=>d.name===want)?.name || ready[0].name;
    sel.value = dump;
    sel.onchange = ()=>{ dump = sel.value; TREES=null; loadDump(); };
  } else {
    DUMPS = [INLINE.stats];
    dump = INLINE.name;
    document.querySelector('#tabs button[data-t="compare"]').style.display = "none";
    document.querySelector('#tabs button[data-t="remote"]').style.display = "none";
  }
  await loadDump();
  pollJobs();
}

async function loadDump(){
  if(API){
    let t;
    try{ t = await jget(`/api/${dump}/trees`); }
    catch(e){ document.getElementById("hsub").textContent = `${dump}: ${e.message}`; return; }
    STATS = t.stats; TREES = t.trees;
  } else {
    STATS = INLINE.stats; TREES = INLINE.trees;
    ICLS = INLINE.classes.map(a=>({disp:a[0], c:a[1], s:a[2], pi:a[1]?Math.round(a[2]/a[1]*10)/10:0,
      r:a[3], comp:!!a[4], anat:a[5]||[], lams:a[6], analyzable:!a[0].endswith("$$Lambda*"),
      name:a[0].split(".").pop(), pkg:a[0].includes(".")?a[0].slice(0,a[0].lastIndexOf(".")):"(no package)",
      cat:catOfName(a[0])}));
  }
  document.getElementById("hsub").textContent =
    `${dump} · ${STATS.dump||""} · Eclipse MAT extracts`;
  renderStats(); renderFindings(); renderLegend(); renderTreemap(); loadClasses();
}

function renderStats(){
  document.getElementById("stats").innerHTML = [
    [fmtB(STATS.totalRetained), "reachable heap", "live objects at dump time (OOM dump ⇒ at ceiling)"],
    [fmtN(STATS.totalObjects), "live objects", "in "+fmtN(STATS.classes)+" classes"],
    [fmtN(STATS.modules), "Gradle projects", "module count for per-module figures"],
    ["≈ "+fmtB(STATS.totalRetained/STATS.modules), "heap per module",
     STATS.buildFileBytes ? "build script ≈ "+fmtB(STATS.buildFileBytes)+" of text" : "avg live heap per Gradle project"],
    [STATS.analyzed, "analyzed classes", "with retained-set composition"],
  ].map(s=>`<div class="stat"><b>${s[0]}</b><span>${s[1]}</span><em>${s[2]}</em></div>`).join("");
}

/* ============================== main tabs ============================== */
document.getElementById("tabs").querySelectorAll("button").forEach(b=>b.onclick=()=>{
  document.getElementById("tabs").querySelectorAll("button").forEach(x=>x.classList.toggle("on",x===b));
  document.querySelectorAll(".tabpane").forEach(p=>p.classList.toggle("on", p.id==="tab-"+b.dataset.t));
  if(b.dataset.t==="treemap") renderTreemap();
  if(b.dataset.t==="remote") loadRemote();
});

/* ============================== findings (auto) ============================== */
function renderFindings(){
  if(!TREES) return;
  const leaves = (n,acc)=>{ if(n.leaf) acc.push(n); else n.children.forEach(c=>leaves(c,acc)); return acc; };
  const dom = leaves(TREES.dom,[]).sort((a,b)=>b.r-a.r).slice(0,8);
  const hist = leaves(TREES.hist,[]).sort((a,b)=>b.s-a.s).slice(0,8);
  const row = (n,m)=>`<tr><td title="${esc(n.disp)}">${esc(n.name)}</td><td>${fmtN(n.c)}</td><td>${(n.c/STATS.modules).toFixed(1)}</td><td>${fmtB(n[m])}</td><td>${fmtB(n[m]/STATS.modules)}</td></tr>`;
  document.getElementById("findings").innerHTML = `
  <h2>What this dump says</h2>
  <p>${fmtB(STATS.totalRetained)} of reachable heap held by ${fmtN(STATS.totalObjects)} objects across
  ~${fmtN(STATS.modules)} projects — ≈ <b>${fmtB(STATS.totalRetained/STATS.modules)} of live heap per module</b>.
  Attribution below is by <b>dominator</b> (an object owns the collections it references); the shallow side shows where raw bytes live.
  Click a row in the Classes tab to drill into any class.</p>
  <div style="display:grid;grid-template-columns:1fr 1fr;gap:24px;flex-wrap:wrap">
  <div><h2 style="font-size:13px">Top retained (top-level dominators)</h2>
  <table><tr><th>class</th><th>instances</th><th>per module</th><th>retained</th><th>per module</th></tr>
  ${dom.map(n=>row(n,"r")).join("")}</table></div>
  <div><h2 style="font-size:13px">Top shallow (all classes)</h2>
  <table><tr><th>class</th><th>instances</th><th>per module</th><th>shallow</th><th>per module</th></tr>
  ${hist.map(n=>row(n,"s")).join("")}</table></div>
  </div>
  <p style="color:#8b94a3;margin-top:18px">Method: Eclipse MAT headless (<code>histogram</code>, <code>dominator_tree</code>,
  <code>show_retained_set</code>, OQL <code>getFields()</code>) over <code>${esc(STATS.dump||"")}</code>.
  On-demand class analysis runs from this UI (serial MAT job queue on the server). Raw CSVs: <code>data/</code> next to this report.
  Lambda specializations (<code>$$Lambda+0x…</code>) are merged — the hex suffix is a per-run address and only adds noise.</p>`;
}

addEventListener("resize", ()=>renderTreemap());
