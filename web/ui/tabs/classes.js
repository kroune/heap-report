/* Classes tab — server-paged class table.
   Filter/sort/page go through classes(id, {filter, sort, page}); the row's
   "viz ▸" button opens the viz popup (switch kinds inside the popup — see
   viz/common.js); Analyze queues a server job (the jobs panel shows progress)
   and the table refreshes once the job finishes.

   Loading never hides the card (hiding it would steal focus from the filter
   input mid-typing): the first load paints shimmer skeleton rows, later loads
   dim the stale rows (.busy) until the new page arrives. Sort is bidirectional:
   clicking the active column toggles its direction ('s' vs '-s'). */
import {esc, fmtB, fmtN, catOf} from '../../data/http.js';
import {listDumps, pollJobs} from '../../data/dumprepo.js';
import * as ddr from '../../data/dumpdatarepo.js';
import {getDump, onDumpChange} from '../../app/state.js';
import {openViz} from '../../viz/common.js';

const CAT_IDS = {gradle: 1, agp: 1, kotlin: 1, jdk: 1, other: 1};

export function catCls(id){
  return 'c-' + (CAT_IDS[id] ? id : 'other');
}

/* null when the dump can be queried, otherwise {msg, err} describing why not.
   Busy states (downloading/assembling/indexing) are NOT blocked here: the
   overview works off the tiny data bundle, which lands seconds into the
   download — the API itself answers 409 until it is unpacked. */
export async function dumpNotReady(id){
  const r = await listDumps();
  if(!r.ok) return {msg: 'failed to list dumps: ' + (r.error || 'unknown error'), err: true};
  const info = (r.data || []).find(d => d.id === id);
  if(info && (info.state === 'failed' || info.state === 'remote'))
    return {msg: `dump "${id}" is ${info.state}` + (info.error ? ` — ${info.error}` : '') + '.',
            err: info.state === 'failed'};
  return null;
}

/* sortable columns: key without direction; '-' prefix = descending.
   Defaults: name ascends, numeric columns descend. */
const COLS = [
  ['name', 'class'], ['c', 'objects'], ['s', 'shallow'], ['pi', 'per inst'],
  ['r', 'retained'], [null, 'per inst'], [null, 'analysis'], [null, ''],
];
const PAGE_COLS = 8;

export function mount(container, repo, opts = {}){
  const R = repo || ddr;                 // INLINE mode passes makeInlineRepo()
  const st = {filter: '', sort: '-s', page: 0, pages: 1, total: 0, rows: [], dump: null, seq: 0};
  const pending = new Map();   // analyze job id -> className
  let stopPoll = null;

  /* ---- skeleton ---- */
  const card = document.createElement('div'); card.className = 'card';
  const controls = document.createElement('div'); controls.className = 'controls';
  const filter = document.createElement('input');
  filter.type = 'text'; filter.className = 'cls-filter';
  filter.placeholder = 'filter classes, e.g. CachePolicy or com.android…';
  const hint = document.createElement('span'); hint.className = 'hint';
  hint.textContent = 'retained/per-instance appear once a class is analyzed (click Analyze or open a viz). ' +
    'Lambda specializations are merged into $$Lambda* families.';
  const note = document.createElement('span'); note.className = 'cls-note';
  const pager = document.createElement('div'); pager.className = 'pager';
  const count = document.createElement('span');
  const prev = document.createElement('button'); prev.textContent = '‹';
  const pageLbl = document.createElement('span');
  const next = document.createElement('button'); next.textContent = '›';
  pager.appendChild(count); pager.appendChild(prev); pager.appendChild(pageLbl); pager.appendChild(next);
  controls.appendChild(filter); controls.appendChild(hint); controls.appendChild(note); controls.appendChild(pager);
  const scroll = document.createElement('div'); scroll.className = 'tbl-scroll';
  const tbl = document.createElement('table'); tbl.className = 'cls';
  scroll.appendChild(tbl);
  card.appendChild(controls); card.appendChild(scroll);
  const msg = document.createElement('div'); msg.className = 'tabmsg';
  container.appendChild(card); container.appendChild(msg);

  function showMsg(text, isErr){
    msg.textContent = text;
    msg.className = 'tabmsg on' + (isErr ? ' err' : '');
    card.classList.add('hidden');
  }
  function hideMsg(){
    msg.className = 'tabmsg';
    card.classList.remove('hidden');
  }
  function setNote(text){ note.textContent = text; }

  function headHtml(){
    return '<tr>' + COLS.map(([s, l]) => {
      if(!s) return `<th>${l}</th>`;
      const on = st.sort === s || st.sort === '-' + s;
      const arrow = on ? (st.sort.startsWith('-') ? ' ▾' : ' ▴') : '';
      return `<th class="sortable${on ? ' on' : ''}" data-s="${s}">${l}${arrow}</th>`;
    }).join('') + '</tr>';
  }

  /* loading state: shimmer skeleton when there's nothing to show yet,
     dimmed stale rows otherwise — the card (and filter focus) never moves */
  function setBusy(on){
    scroll.classList.toggle('busy', on);
    if(on && !st.rows.length){
      const row = `<tr>${`<td><span class="skl"></span></td>`.repeat(PAGE_COLS)}</tr>`;
      tbl.innerHTML = headHtml() + row.repeat(12);
      count.textContent = '…';
    }
  }
  function renderError(text){
    tbl.innerHTML = headHtml() +
      `<tr><td colspan="${PAGE_COLS}" class="pad err">${esc(text)}</td></tr>`;
    count.textContent = '';
    pageLbl.textContent = '';
    prev.disabled = true;
    next.disabled = true;
  }

  /* ---- data ---- */
  async function load(){
    const my = ++st.seq;
    const id = getDump();
    st.dump = id;
    setNote('');
    if(!id){ showMsg('no dump selected — pick a dump in the selector above.'); return; }
    showMsg('loading…');
    const nr = opts.inline ? null : await dumpNotReady(id);   // snapshots are READY by construction
    if(my !== st.seq) return;
    if(nr){ showMsg(nr.msg, nr.err); return; }
    await loadPage();
  }

  async function loadPage(){
    const my = ++st.seq;
    const id = st.dump;
    if(!id){ showMsg('no dump selected — pick a dump in the selector above.'); return; }
    hideMsg();
    setBusy(true);
    const r = await R.classes(id, {filter: st.filter, sort: st.sort, page: st.page});
    if(my !== st.seq) return;
    setBusy(false);
    if(!r.ok){
      renderError(`${id}: ${r.error || 'failed to load classes'}`);
      // busy dump, data bundle not unpacked yet — retry until it lands (the
      // retry re-checks dumpNotReady, so failed/remote dumps terminate here)
      if(r.status === 409 && !opts.inline)
        setTimeout(() => { if(st.seq === my && st.dump === getDump()) load(); }, 4000);
      return;
    }
    st.rows = r.data.rows || [];
    st.total = r.data.total || 0;
    st.pages = Math.max(1, r.data.pages || 1);
    renderTable();
  }

  /* ---- render ---- */
  function rowHtml(r){
    const disp = String(r.disp || '');
    const name = r.name || disp.split('.').pop();
    const pkg = r.pkg || (disp.includes('.') ? disp.slice(0, disp.lastIndexOf('.')) : '(no package)');
    const cat = catCls(r.cat || catOf(disp));
    const anat = r.anat || [];
    const badges = (r.comp ? '<span class="bdg on" title="retained-set composition available">rs</span>' : '') +
      anat.map(k => `<span class="bdg on" title="anatomy extracted with ${esc(String(k))} samples">a${esc(String(k))}</span>`).join('');
    const lam = r.lams ? `<span class="lamexp" data-l="${esc(disp)}">▸ ${r.lams.length}</span>` : '';
    // the Analyze button covers the "composition/anatomy 404 {analyzed:false}" case:
    // the class is analyzable but has no analysis artifacts yet.
    const anBtn = (!opts.inline && r.analyzable && !r.comp && !anat.length)
      ? `<button class="an-btn" data-an="${esc(disp)}">Analyze</button>` : '';
    const vizBtn = `<button class="vzbtn" data-viz="anatomy" data-n="${esc(disp)}" ` +
      `title="open visualizations for this class (switch kinds inside the popup)">viz ▸</button>`;
    let h = `<tr>
      <td class="cname" data-n="${esc(disp)}"><span class="catdot ${cat}"></span><span title="${esc(pkg)}">${esc(name)}</span>${lam}</td>
      <td class="num">${fmtN(r.c || 0)}</td><td class="num">${fmtB(r.s || 0)}</td><td class="num">${fmtB(r.pi || 0)}</td>
      <td class="num">${r.r != null ? fmtB(r.r) : '—'}</td><td class="num">${r.r != null ? fmtB(r.r / Math.max(1, r.c || 1)) : '—'}</td>
      <td>${badges}</td>
      <td class="acts">${vizBtn}${anBtn}</td></tr>`;
    if(r.lams) h += `<tr class="lamrow" data-lrow="${esc(disp)}"><td colspan="${PAGE_COLS}">${
      r.lams.map(l => `<div>${esc(String(l[0]))} — ${fmtN(l[1])} objs · ${fmtB(l[2])}</div>`).join('')}</td></tr>`;
    return h;
  }

  function renderTable(){
    const body = st.total === 0
      ? `<tr><td colspan="${PAGE_COLS}" class="pad">no classes match the current filter.</td></tr>`
      : st.rows.map(rowHtml).join('');
    tbl.innerHTML = headHtml() + body;
    count.textContent = `${fmtN(st.total)} classes`;
    pageLbl.textContent = `${st.page + 1}/${st.pages}`;
    prev.disabled = st.page <= 0;
    next.disabled = st.page >= st.pages - 1;
  }

  /* ---- analyze jobs ---- */
  async function startAnalyze(className){
    const id = st.dump;
    if(!id) return;
    setNote(`queuing analysis of ${className}…`);
    const r = await R.analyze(id, className);
    if(!r.ok){ setNote(`analyze failed: ${r.error || 'unknown error'}`); return; }
    const job = r.data;
    setNote(`analysis of ${className} queued — progress in the jobs panel.`);
    if(job && job.id != null && job.state !== 'done'){
      pending.set(job.id, className);
      watchJobs();
    } else {
      R.invalidate(id);
      loadPage();
    }
  }

  function watchJobs(){
    if(stopPoll) return;
    stopPoll = pollJobs(jobs => {
      let finished = false, failed = null;
      for(const j of (jobs || [])){
        if(!pending.has(j.id)) continue;
        if(j.state === 'done'){ pending.delete(j.id); finished = true; }
        else if(j.state === 'failed'){ failed = {error: j.error, cls: pending.get(j.id)}; pending.delete(j.id); }
      }
      if(failed) setNote(`analysis of ${failed.cls} failed: ${failed.error || 'see jobs panel'}`);
      if(finished){
        R.invalidate(st.dump);
        setNote('analysis finished — table refreshed.');
        loadPage();
      }
      if(!pending.size && stopPoll){ stopPoll(); stopPoll = null; }
    });
  }

  /* ---- wiring ---- */
  let timer = 0;
  filter.addEventListener('input', () => {
    clearTimeout(timer);
    timer = setTimeout(() => { st.filter = filter.value.trim().toLowerCase(); st.page = 0; loadPage(); }, 180);
  });
  prev.addEventListener('click', () => { if(st.page > 0){ st.page--; loadPage(); } });
  next.addEventListener('click', () => { if(st.page < st.pages - 1){ st.page++; loadPage(); } });

  tbl.addEventListener('click', e => {
    const t = e.target;
    if(!t || !t.closest) return;
    const th = t.closest('th.sortable');
    if(th){
      const key = th.dataset.s;
      const active = st.sort === key || st.sort === '-' + key;
      // repeat click on the active column toggles direction; a new column gets
      // its default (name ascends, numeric columns descend)
      st.sort = active ? (st.sort.startsWith('-') ? key : '-' + key)
                       : (key === 'name' ? key : '-' + key);
      st.page = 0;
      loadPage();
      return;
    }
    const lam = t.closest('.lamexp');
    if(lam){
      const row = tbl.querySelector(`tr.lamrow[data-lrow="${CSS.escape(lam.dataset.l)}"]`);
      if(row) row.classList.toggle('open');
      return;
    }
    const vb = t.closest('[data-viz]');
    if(vb){ openViz(vb.dataset.viz, st.dump, vb.dataset.n); return; }
    const ab = t.closest('[data-an]');
    if(ab){ startAnalyze(ab.dataset.an); return; }
    const td = t.closest('td.cname');
    if(td) openViz('anatomy', st.dump, td.dataset.n);
  });

  onDumpChange(() => {
    st.filter = ''; filter.value = ''; st.sort = '-s'; st.page = 0;
    st.rows = []; st.total = 0;
    load();
  });
  load();
}
