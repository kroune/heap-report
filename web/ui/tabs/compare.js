/* Compare tab — two-dump diff: reachable-heap stats, freed→absorbed waterfall,
   dominator / retained-set / anatomy deltas, and the raw class-delta drill table
   with per-row "rs diff" retained-set composition diff. */
import {esc, fmtB, fmtN} from '../../data/http.js';
import {listDumps} from '../../data/dumprepo.js';
import * as ddr from '../../data/dumpdatarepo.js';
import {onDumpChange} from '../../app/state.js';

export const fmtS = v => (v > 0 ? '+' : v < 0 ? '−' : '') + fmtB(Math.abs(v));
export const shortName = n => String(n).split('.').pop();

const SORTS = [['abs', '|Δ shallow|'], ['down', 'biggest decrease'], ['up', 'biggest increase'],
               ['cdown', 'count decrease'], ['cup', 'count increase']];
export function sortRows(rows, mode){
  const fns = {abs: (a, b) => Math.abs(b[6]) - Math.abs(a[6]), down: (a, b) => a[6] - b[6],
               up: (a, b) => b[6] - a[6], cdown: (a, b) => a[3] - b[3], cup: (a, b) => b[3] - a[3]};
  return [...rows].sort(fns[mode] || fns.abs);
}

const LAM = /\$\$Lambda\+0x[0-9a-f]+$/;   // per-run address — merge specializations like the histogram does
// note: the replacement is a function — '$$' in a replacement string is a
// literal '$', so a plain-string replace would silently merge into '$Lambda*'.
/* Merge two composition payloads into [name, oldShallow, newShallow, Δshallow, oldObjs, newObjs]. */
export function rsDiffRows(co, cn){
  const m = new Map();
  const put = (r, i) => {
    const k = String(r[0]).replace(LAM, () => '$$Lambda*');
    const e = m.get(k) || [0, 0, 0, 0];
    e[i] += r[1]; e[i + 2] += r[2];
    m.set(k, e);
  };
  for(const r of (co.rows || [])) put(r, 0);
  for(const r of (cn.rows || [])) put(r, 1);
  return [...m.entries()].map(([k, e]) => [k, e[0], e[1], e[1] - e[0], e[2], e[3]])
    .sort((a, b) => Math.abs(b[3]) - Math.abs(a[3]));
}

const sleep = ms => new Promise(res => setTimeout(res, ms));

/* ---- pure HTML builders (all data-derived strings esc()'d) ---- */
function barHtml(v, mx, cls){
  return `<div class="track"><div class="bar ${cls}" style="width:${(100 * Math.abs(v) / mx).toFixed(1)}%"></div></div>`;
}

function wfSide(title, rows, rest, restN, sum, mx, cls){
  let h = `<div><h4>${title} — ${fmtB(Math.abs(sum))}</h4>`;
  for(const [name, v] of rows)
    h += `<div class="wfrow"><div class="nm" title="${esc(name)}">${esc(shortName(name))}</div>${barHtml(v, mx, cls)}<div class="num ${v < 0 ? 'neg' : 'pos'}">${fmtS(v)}</div></div>`;
  if(restN > 0)
    h += `<div class="wfrow"><div class="nm wftail">· ${fmtN(restN)} more classes (long tail)</div>${barHtml(rest, mx, cls)}<div class="num ${rest < 0 ? 'neg' : 'pos'}">${fmtS(rest)}</div></div>`;
  h += `<div class="wfrow total"><div>Σ ${title.toLowerCase()}</div>${barHtml(sum, mx, cls)}<div class="num">${fmtS(sum)}</div></div></div>`;
  return h;
}

function waterfallHtml(wf){
  const freedSum = wf.freedSum || 0, absorbedSum = wf.absorbedSum || 0;
  const mx = Math.max(-freedSum, absorbedSum, 1);
  return `<h3 class="sec">Freed → absorbed <span class="secsub">(both heaps at the OOM ceiling: every freed byte is re-absorbed by something — the two sides must balance)</span></h3>
  <div class="wf">
    ${wfSide('Freed', wf.freed || [], wf.freedRest || 0, wf.freedRestN || 0, freedSum, mx, 'free')}
    ${wfSide('Absorbed', wf.absorbed || [], wf.absorbedRest || 0, wf.absorbedRestN || 0, absorbedSum, mx, 'absorb')}
  </div>
  <div class="hint wfhint">Σ freed ${fmtB(-freedSum)} vs Σ absorbed ${fmtB(absorbedSum)} — net ${fmtS(freedSum + absorbedSum)} (≈ how far the ceiling itself moved). Long-tail sums shown explicitly: many small movers can outweigh the top rows.</div>`;
}

function rsBtnHtml(full){
  return String(full).endsWith('$$Lambda*') ? ''
    : `<button class="rsbtn" data-rs="${esc(full)}" title="diff the retained-set composition of this class between the two dumps (runs analysis where missing)">rs diff</button>`;
}

function domHtml(dom){
  return `<h3 class="sec">Dominator deltas <span class="secsub">(top-level dominators only — owned memory, closest to the cause; MAT rolls dominated objects into their owner)</span></h3>
  <table class="cmp"><tr><th>class</th><th>old objs</th><th>new objs</th><th>old retained</th><th>new retained</th><th>Δ retained</th><th>Δ shallow</th></tr>
  ${dom.slice(0, 200).map(r => {
    const ds = r[4] - r[3], dr = r[7];
    return `<tr><td class="cname" title="${esc(r[0])}">${esc(shortName(r[0]))}</td>
    <td class="num">${fmtN(r[1])}</td><td class="num">${fmtN(r[2])}</td>
    <td class="num">${fmtB(r[5])}</td><td class="num">${fmtB(r[6])}</td>
    <td class="num ${dr < 0 ? 'neg' : dr > 0 ? 'pos' : ''}">${fmtS(dr)}</td>
    <td class="num ${ds < 0 ? 'neg' : ds > 0 ? 'pos' : ''}">${fmtS(ds)}</td></tr>`;}).join('')}</table>
  <div class="hint cmphint">top 200 by |Δ retained| · ${fmtN(dom.length)} dominator classes total</div>`;
}

function anatDiffHtml(anats){
  const keys = Object.keys(anats);
  if(!keys.length) return '';
  let h = `<h3 class="sec">Anatomy diffs <span class="secsub">(per sampled instance, field-path matched — what changed inside a typical instance; instance-count changes are in the class deltas)</span></h3>`;
  for(const full of keys){
    const d = anats[full];
    const rows = (d.rows || []).map(r => {
      const parts = String(r[0]).split('/');
      const depth = Math.min(parts.length - 2, 10);
      return `<tr><td class="cname dep${depth}" title="${esc(r[0])}">${esc(parts.pop())}</td>
      <td class="num">${fmtB(r[1])}</td><td class="num">${fmtB(r[2])}</td>
      <td class="num ${r[3] < 0 ? 'neg' : r[3] > 0 ? 'pos' : ''}">${fmtS(r[3])}</td>
      <td class="num">${fmtB(r[4])}</td><td class="num">${fmtB(r[5])}</td>
      <td class="num ${r[6] < 0 ? 'neg' : r[6] > 0 ? 'pos' : ''}">${fmtS(r[6])}</td></tr>`;}).join('');
    h += `<details class="cmpd"><summary>${esc(shortName(full))} — ${fmtN(d.total)} changed paths · samples s${d.samples[0]} → s${d.samples[1]}</summary>
      <table class="cmp"><tr><th>field path (per-instance values)</th><th>old shallow</th><th>new shallow</th><th>Δ shallow</th><th>old retained</th><th>new retained</th><th>Δ retained</th></tr>
      ${rows}</table>
      <div class="hint cmphint">${d.total > (d.rows || []).length ? `top ${d.rows.length} of ${fmtN(d.total)} changed paths by |Δ| · ` : ''}string-value labels differ per run → same-content strings show as remove+add pairs; "(held via untracked/shared refs)" is memory owned by others that leaked into the sample</div></details>`;
  }
  return h;
}

function rsDiffHtml(full, co, cn){
  const rows = rsDiffRows(co, cn);
  const dt = (cn.totalShallow || 0) - (co.totalShallow || 0);
  return `<div class="rsdiff">
    <div class="rshead">retained set of <b title="${esc(full)}">${esc(shortName(full))}</b>:
      ${fmtB(co.totalShallow || 0)} → ${fmtB(cn.totalShallow || 0)}
      <b class="${dt < 0 ? 'neg' : dt > 0 ? 'pos' : ''}">${fmtS(dt)}</b>
      · ${fmtN(co.totalObjects || 0)} → ${fmtN(cn.totalObjects || 0)} objects</div>
    <table class="cmp"><tr><th>inside the retained set</th><th>old shallow</th><th>new shallow</th><th>Δ shallow</th><th>old objs</th><th>new objs</th></tr>
    ${rows.slice(0, 30).map(r => `<tr><td class="cname" title="${esc(r[0])}">${esc(shortName(r[0]))}</td>
      <td class="num">${fmtB(r[1])}</td><td class="num">${fmtB(r[2])}</td>
      <td class="num ${r[3] < 0 ? 'neg' : r[3] > 0 ? 'pos' : ''}">${fmtS(r[3])}</td>
      <td class="num">${fmtN(r[4])}</td><td class="num">${fmtN(r[5])}</td></tr>`).join('')}</table>
    <div class="hint cmphint">top 30 inner movers of ${fmtN(rows.length)} classes seen in either retained set (each side lists its top 100 by shallow; the totals above are complete)</div>
  </div>`;
}

function cmpDeltaRowHtml(r){
  const dc = r[3], ds = r[6];
  const cls = ds < 0 ? 'neg' : ds > 0 ? 'pos' : '';
  return `<tr><td class="cname" title="${esc(r[0])}">${esc(shortName(r[0]))}${rsBtnHtml(r[0])}</td>
    <td class="num">${fmtN(r[1])}</td><td class="num">${fmtN(r[2])}</td>
    <td class="num ${dc < 0 ? 'neg' : dc > 0 ? 'pos' : ''}">${dc > 0 ? '+' : ''}${fmtN(dc)}</td>
    <td class="num">${fmtB(r[4])}</td><td class="num">${fmtB(r[5])}</td>
    <td class="num ${cls}">${fmtS(ds)}</td></tr>`;
}

export function mount(container, repo, opts = {}){
  const R = repo || ddr;                 // INLINE mode passes makeInlineRepo()
  let CMP = null, CMPSORT = 'abs', seq = 0, cseq = 0;

  /* ---- skeleton ---- */
  const wrap = document.createElement('div'); wrap.className = 'card cmp-wrap';
  const controls = document.createElement('div'); controls.className = 'controls cmp-controls';
  const selOld = document.createElement('select');
  const arrow = document.createElement('span'); arrow.className = 'cmp-arrow'; arrow.textContent = '→';
  const selNew = document.createElement('select');
  const run = document.createElement('button'); run.className = 'pri'; run.textContent = 'Compare';
  const hint = document.createElement('span'); hint.className = 'hint';
  hint.textContent = 'freed → absorbed = zero-sum at the OOM ceiling · dominator = owned memory · "rs diff" on a row drills into its retained-set diff';
  controls.appendChild(selOld); controls.appendChild(arrow); controls.appendChild(selNew);
  controls.appendChild(run); controls.appendChild(hint);
  const out = document.createElement('div'); out.className = 'cmp-out';
  wrap.appendChild(controls); wrap.appendChild(out);
  const msg = document.createElement('div'); msg.className = 'tabmsg';
  container.appendChild(wrap); container.appendChild(msg);

  function showMsg(text, isErr){
    msg.textContent = text;
    msg.className = 'tabmsg on' + (isErr ? ' err' : '');
    wrap.classList.add('hidden');
  }
  function hideMsg(){
    msg.className = 'tabmsg';
    wrap.classList.remove('hidden');
  }

  /* ---- dump selectors (only 'ready' dumps are selectable) ---- */
  async function refreshList(){
    if(opts.inline){ showMsg('compare is not available in a static snapshot.'); return; }
    const my = ++seq;
    showMsg('loading dump list…');
    const r = await listDumps();
    if(my !== seq) return;
    if(!r.ok){ showMsg('failed to list dumps: ' + (r.error || 'unknown error'), true); return; }
    const ready = (r.data || []).filter(d => d.state === 'ready');
    const prevO = selOld.value, prevN = selNew.value;
    selOld.innerHTML = ''; selNew.innerHTML = '';
    for(const d of ready){
      for(const sel of [selOld, selNew]){
        const o = document.createElement('option');
        o.value = d.id; o.textContent = d.id;
        sel.appendChild(o);
      }
    }
    const ids = new Set(ready.map(d => d.id));
    selOld.value = ids.has(prevO) ? prevO : (ready[0] || {id: ''}).id;
    selNew.value = ids.has(prevN) ? prevN : (ready[1] || ready[0] || {id: ''}).id;
    if(ready.length < 2){
      showMsg(`compare needs two ready dumps — only ${ready.length} ready right now.`);
      return;
    }
    hideMsg();
  }

  /* ---- compare ---- */
  async function runCompare(){
    const o = selOld.value, n = selNew.value;
    if(!o || !n) return;
    if(o === n){ out.innerHTML = '<div class="pad err">pick two different dumps to compare.</div>'; return; }
    const my = ++cseq;
    out.innerHTML = `<div class="pad">comparing ${esc(o)} → ${esc(n)} …</div>`;
    const r = await R.compare(o, n);
    if(my !== cseq) return;
    if(!r.ok){ out.innerHTML = `<div class="pad err">compare failed: ${esc(r.error || 'unknown error')}</div>`; return; }
    CMP = r.data;
    CMP.oldId = o; CMP.newId = n;
    CMPSORT = 'abs';
    paint();
  }

  function paint(){
    const c = CMP;
    if(!c) return;
    const oldS = (c.old && c.old.totalShallow) || 0, newS = (c.new && c.new.totalShallow) || 0;
    const oldO = (c.old && c.old.totalObjects) || 0, newO = (c.new && c.new.totalObjects) || 0;
    let html = `<div class="stats cmp-stats">
      <div class="stat"><b>${fmtB(oldS)} → ${fmtB(newS)}</b><span>reachable heap (both OOM ⇒ both at ceiling)</span><em>Δ ${fmtS(newS - oldS)} — meaningless at the ceiling, see freed → absorbed below</em></div>
      <div class="stat"><b>${fmtN(oldO)} → ${fmtN(newO)}</b><span>live objects</span><em>Δ ${fmtS(newO - oldO)}</em></div>
    </div>`;
    html += waterfallHtml(c.waterfall || {});
    html += `<h3 class="sec">Build progress proxies <span class="secsub">(did the new run get further before OOM?)</span></h3>
      <table class="cmp"><tr><th>class</th><th>old objects</th><th>new objects</th><th>Δ</th><th>old shallow</th><th>new shallow</th></tr>
      ${(c.proxies || []).map(p => {
        const dc = p[2] - p[1];
        return `<tr><td class="cname" title="${esc(p[0])}">${esc(shortName(p[0]))}</td>
          <td class="num">${fmtN(p[1])}</td><td class="num">${fmtN(p[2])}</td>
          <td class="num ${dc < 0 ? 'neg' : dc > 0 ? 'pos' : ''}">${dc > 0 ? '+' : ''}${fmtN(dc)}</td>
          <td class="num">${fmtB(p[3])}</td><td class="num">${fmtB(p[4])}</td></tr>`;}).join('')}</table>`;
    html += domHtml(c.dom || []);
    const retained = c.retained || [];
    if(retained.length){
      html += `<h3 class="sec">Retained-set deltas <span class="secsub">(classes analyzed in both dumps — the trustworthy comparison)</span></h3>
        <table class="cmp"><tr><th>class</th><th>old retained set</th><th>new retained set</th><th>Δ</th></tr>
        ${retained.map(r => `<tr><td class="cname" title="${esc(r[0])}">${esc(shortName(r[0]))}${rsBtnHtml(r[0])}</td>
          <td class="num">${fmtB(r[1])}</td><td class="num">${fmtB(r[2])}</td>
          <td class="num ${r[3] < 0 ? 'neg' : r[3] > 0 ? 'pos' : ''}">${fmtS(r[3])}</td></tr>`).join('')}</table>`;
    }
    html += anatDiffHtml(c.anats || {});
    const rows = c.rows || [];
    const sorted = sortRows(rows, CMPSORT);
    html += `<h3 class="sec">Class deltas — raw histogram (lambda-normalized)
        <span class="cmpsort">sort: ${SORTS.map(([k, l]) =>
          `<a href="#" data-cs="${k}"${CMPSORT === k ? ' class="on"' : ''}>${l}</a>`).join(' · ')}</span></h3>
      <table class="cmp"><tr><th>class</th><th>old objs</th><th>new objs</th><th>Δ objs</th><th>old shallow</th><th>new shallow</th><th>Δ shallow</th></tr>
      ${sorted.slice(0, 400).map(cmpDeltaRowHtml).join('')}</table>
      <div class="hint cmphint">top 400 by current sort · ${fmtN(rows.length)} classes total · this table is zero-sum noise at the ceiling — the sections above explain it</div>`;
    out.innerHTML = html;
  }

  /* ---- rs drill-down: analyze in both dumps if needed, then diff compositions ---- */
  async function drillRs(btn, full){
    const tr = btn.closest('tr');
    if(!tr) return;
    const next = tr.nextElementSibling;
    if(next && next.classList.contains('drill')){ next.remove(); return; }
    const myCmp = CMP;
    const cell = document.createElement('tr'); cell.className = 'drill';
    const td = document.createElement('td');
    td.setAttribute('colspan', '8');
    td.textContent = '…';
    cell.appendChild(td);
    tr.parentNode.insertBefore(cell, tr.nextSibling);
    const analyzed = side => ((myCmp.analyzed || {})[side]) || [];
    for(const side of ['old', 'new']){
      if(analyzed(side).includes(full)) continue;
      const dumpId = side === 'old' ? myCmp.oldId : myCmp.newId;
      td.textContent = `queuing retained-set analysis of ${shortName(full)} in ${dumpId} (composition-only; progress in the jobs panel)…`;
      const r = await R.analyze(dumpId, full, {samples: 8, anatomy: false});
      if(CMP !== myCmp) return;
      if(!r.ok){ td.textContent = `analyze failed: ${r.error || 'unknown error'}`; return; }
    }
    const t0 = Date.now();
    let co = null, cn = null;
    while(Date.now() - t0 < 15 * 60 * 1000){
      const [ro, rn] = await Promise.all([
        R.composition(myCmp.oldId, full), R.composition(myCmp.newId, full)]);
      if(CMP !== myCmp) return;
      for(const [side, r] of [['old', ro], ['new', rn]]){
        if(!r.ok && r.status !== 404){
          td.textContent = `composition query failed (${side}): ${r.error || 'unknown error'}`;
          return;
        }
      }
      co = ro.ok ? ro.data : null;
      cn = rn.ok ? rn.data : null;
      if(co && cn) break;
      td.textContent = `waiting for retained-set analysis (${co ? '✓' : '…'} old / ${cn ? '✓' : '…'} new)…`;
      await sleep(4000);
    }
    if(!co || !cn){ td.textContent = 'timed out waiting for analysis — check the jobs panel.'; return; }
    if(!myCmp.analyzed) myCmp.analyzed = {old: [], new: []};
    for(const side of ['old', 'new'])
      if(!myCmp.analyzed[side].includes(full)) myCmp.analyzed[side].push(full);
    td.innerHTML = rsDiffHtml(full, co, cn);
  }

  /* ---- wiring ---- */
  run.addEventListener('click', runCompare);
  out.addEventListener('click', e => {
    const t = e.target;
    if(!t || !t.closest) return;
    const cs = t.closest('[data-cs]');
    if(cs){ e.preventDefault(); CMPSORT = cs.dataset.cs; paint(); return; }
    const rs = t.closest('.rsbtn');
    if(rs) drillRs(rs, rs.dataset.rs);
  });
  onDumpChange(refreshList);   // a dump becoming ready elsewhere enlarges the selectable set
  refreshList();
}
