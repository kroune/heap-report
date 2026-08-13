"use strict";
/* ============================== remote runs tab ==============================
   Discovers benchmark-repo `run-*` releases (daemon heap dumps) joined with
   index-repo `idx-*` releases (MAT indexes pre-built on CI). Download enqueues
   a server-side job: dump (+indexes when present) -> local bootstrap. */
let REMOTE = null;

async function loadRemote(fresh){
  const tbl = document.getElementById("rem-table");
  if(!REMOTE) tbl.innerHTML = `<tr><td class="pad">querying GitHub…</td></tr>`;
  try{
    REMOTE = await jget("/api/remote" + (fresh ? "?fresh=1" : ""));
    if(REMOTE && REMOTE.error){ tbl.innerHTML = `<tr><td class="pad">${esc(REMOTE.error)}</td></tr>`; return; }
  }catch(e){
    tbl.innerHTML = `<tr><td class="pad">failed to list remote runs: ${esc(e.message)}</td></tr>`; return;
  }
  renderRemote();
}

function _remJob(tag){
  return [...activeJobs.values()].find(j=>j.kind==="download" && j.dump===tag &&
      (j.status==="queued"||j.status==="running"));
}

function renderRemote(){
  const tbl = document.getElementById("rem-table");
  if(!REMOTE || !REMOTE.length){ tbl.innerHTML = `<tr><td class="pad">no runs with heap dumps found</td></tr>`; return; }
  tbl.innerHTML = `<tr><th>run</th><th>title</th><th style="text-align:right">dump (gz)</th><th>indexes</th><th>local</th><th></th></tr>` +
    REMOTE.map(r=>{
      const job = _remJob(r.tag);
      let action;
      if(r.local==="ready") action = `<button class="pri" data-open="${esc(r.tag)}">Open</button>`;
      else if(job) action = `<button disabled>${job.status}…</button>`;
      else if(r.indexed) action = `<button data-dl="${esc(r.tag)}">Download</button>`;
      else action = `<button data-dl="${esc(r.tag)}" title="no prebuilt indexes — the bootstrap runs the full MAT parse locally (~40 min, heavy)">Download + full parse</button>`;
      const idx = r.indexed ? `<span class="bdg on">indexed</span> <span class="hint">${fmtB(r.index_bytes)}</span>`
                            : `<span class="bdg">no indexes</span>`;
      const loc = r.local==="ready" ? `<span class="bdg on">ready</span>`
                : r.local==="downloaded" ? `<span class="bdg">downloaded</span>` : "";
      return `<tr><td class="cname">${esc(r.tag)}</td><td class="cname">${esc(r.title)}</td>` +
        `<td>${fmtB(r.dump_bytes)}</td><td>${idx}</td><td>${loc}</td><td>${action}</td></tr>`;
    }).join("");
  tbl.querySelectorAll("[data-dl]").forEach(b=>b.onclick=()=>startDownload(b.dataset.dl));
  tbl.querySelectorAll("[data-open]").forEach(b=>b.onclick=()=>openDump(b.dataset.open));
}

async function startDownload(tag){
  const r = await fetch("/api/remote/download", {method:"POST",
    headers:{"Content-Type":"application/json"}, body:JSON.stringify({tag})});
  const job = await r.json();
  if(!r.ok){ alert(job.error||"download failed"); return; }
  if(job.already){ await refreshDumps(); loadRemote(); return; }
  activeJobs.set(job.id, job);
  renderRemote(); pollJobs();
}

async function openDump(name){
  await refreshDumps();
  if(!DUMPS.find(d=>d.name===name)) return;
  dump = name;
  const sel = document.getElementById("dumpsel");
  sel.value = name;
  TREES = null;
  document.querySelector('#tabs button[data-t="classes"]').click();
  loadDump();
}

document.getElementById("rem-refresh").onclick = ()=>loadRemote(true);
