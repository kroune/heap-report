/* ui/dumppicker.js — the dump selector: a full-screen overlay opened from the
 * header button (boot.js wires it; Esc / backdrop click / × close it).
 * Search matches id, title and tags; the tag bar filters (AND, multi-select,
 * chips carry counts); sortable by newest/name/size. Tags = derived from the
 * id (run-idea-13-base -> idea, 13, base) + user tags persisted server-side
 * (meta.tags, editable per row: × removes, "+ tag" adds, comma-separated ok).
 * Row actions mirror the dump state (Download / Retry / Resume / Fetch or
 * build data) plus Cancel for dumps with a live download job and Delete for
 * ready/failed local dumps. boot.js owns the
 * dump list — it pushes data via update(); this module renders and calls
 * opts.onRefresh() after lifecycle ops. Styles: app.css (.pk-*, .tagchip). */
import {fmtB, fmtSrc} from '../data/http.js';
import {startDownload, cancelDownload, deleteDump, setTags} from '../data/dumprepo.js';
import {getDump} from '../app/state.js';

/* run-idea-13-base -> [idea, 13, base] — id tokens minus the run prefix */
const derivedTags = id => [...new Set(id.split('-').filter(t => t && t !== 'run'))];

const manualTags = d => (d.meta && Array.isArray(d.meta.tags)) ? d.meta.tags : [];
const allTags = d => [...new Set([...derivedTags(d.id), ...manualTags(d)])];
const dateOf = d => (d.meta && d.meta.created_at) || '';

const SORTS = [['date', 'newest'], ['name', 'name'], ['size', 'size']];

export function mountDumpPicker(opts) {
  /* opts = {onSelect(id), onRefresh()} */
  let dumps = [];
  let liveDl = new Set();   // dump ids with an active download job (from boot)
  let openFlag = false;
  let search = '';
  let active = new Set();   // tag filters (AND)
  let sort = 'date';
  let addingFor = null;     // dump id with an open add-tag input
  let status = '';          // sticky in-overlay error line (terminal state)

  const overlay = document.createElement('div');
  overlay.className = 'picker-overlay';
  overlay.hidden = true;
  const box = document.createElement('div');
  box.className = 'picker';
  overlay.appendChild(box);
  document.body.appendChild(overlay);

  /* ---- head: search, sort, close ---- */
  const head = document.createElement('div');
  head.className = 'pk-head';
  const searchEl = document.createElement('input');
  searchEl.type = 'text';
  searchEl.placeholder = 'Search dumps, tags…';
  searchEl.addEventListener('input', () => {
    search = searchEl.value.trim().toLowerCase();
    renderList();
  });
  const sortEl = document.createElement('select');
  for (const [v, label] of SORTS) {
    const o = document.createElement('option');
    o.value = v;
    o.textContent = 'sort: ' + label;
    sortEl.appendChild(o);
  }
  sortEl.addEventListener('change', () => { sort = sortEl.value; renderList(); });
  const closeBtn = document.createElement('button');
  closeBtn.textContent = '×';
  closeBtn.title = 'Close (Esc)';
  closeBtn.addEventListener('click', close);
  head.append(searchEl, sortEl, closeBtn);

  const tagBar = document.createElement('div');
  tagBar.className = 'pk-tags';
  const statusEl = document.createElement('div');
  statusEl.className = 'pk-status';
  const listEl = document.createElement('div');
  listEl.className = 'pk-list';
  box.append(head, tagBar, statusEl, listEl);

  overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });
  document.addEventListener('keydown', (e) => {
    if (openFlag && e.key === 'Escape') close();
  });

  function open() {
    openFlag = true;
    overlay.hidden = false;
    render();
    searchEl.focus();
  }

  function close() {
    openFlag = false;
    overlay.hidden = true;
  }

  function update(nextDumps, nextLiveDl) {
    dumps = nextDumps;
    liveDl = nextLiveDl;
    if (openFlag) render();
  }

  /* ---- filtering + sorting ---- */

  const matches = (d) => {
    const tags = allTags(d);
    for (const t of active) if (!tags.includes(t)) return false;
    if (!search) return true;
    const hay = (d.id + ' ' + ((d.meta && d.meta.title) || '') + ' '
      + tags.join(' ')).toLowerCase();
    return hay.includes(search);
  };

  const cmp = {
    date: (a, b) => dateOf(b).localeCompare(dateOf(a)),   // '' (unknown) last
    name: (a, b) => a.id.localeCompare(b.id),
    size: (a, b) => (b.size || 0) - (a.size || 0),
  };

  /* ---- rendering ---- */

  function render() {
    statusEl.textContent = status;
    renderTags();
    renderList();
  }

  function renderTags() {
    const counts = new Map();
    for (const d of dumps)
      for (const t of allTags(d)) counts.set(t, (counts.get(t) || 0) + 1);
    const tags = [...counts.entries()]
      .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
    const els = [];
    for (const [t, n] of tags) {
      const c = document.createElement('button');
      c.className = 'tagchip' + (active.has(t) ? ' on' : '');
      c.textContent = t;
      const cnt = document.createElement('span');
      cnt.className = 'cnt';
      cnt.textContent = n;
      c.appendChild(cnt);
      c.addEventListener('click', () => {
        if (active.has(t)) active.delete(t); else active.add(t);
        render();
      });
      els.push(c);
    }
    if (active.size) {
      const clr = document.createElement('button');
      clr.className = 'tagchip pk-clear';
      clr.textContent = 'clear filters';
      clr.addEventListener('click', () => { active.clear(); render(); });
      els.push(clr);
    }
    tagBar.replaceChildren(...els);
  }

  function renderList() {
    const rows = dumps.filter(matches).sort(cmp[sort]);
    if (!rows.length) {
      const pad = document.createElement('div');
      pad.className = 'pad';
      pad.textContent = dumps.length ? 'No dumps match.' : 'No dumps yet.';
      listEl.replaceChildren(pad);
      return;
    }
    listEl.replaceChildren(...rows.map(renderRow));
  }

  function renderRow(d) {
    const row = document.createElement('div');
    row.className = 'pk-row' + (d.id === getDump() ? ' pk-cur' : '');

    const main = document.createElement('div');
    main.className = 'pk-main';
    const idEl = document.createElement('div');
    idEl.className = 'pk-id';
    idEl.textContent = d.id;
    main.appendChild(idEl);
    const title = d.meta && d.meta.title;
    if (title && title !== d.id) {
      const tEl = document.createElement('div');
      tEl.className = 'pk-title';
      tEl.textContent = title;
      main.appendChild(tEl);
    }
    main.appendChild(renderTagRow(d));

    const badge = document.createElement('span');
    badge.className = 'stbadge st-' + d.state;
    badge.textContent = d.state;

    const meta = document.createElement('div');
    meta.className = 'pk-meta';
    if (d.progress && d.progress.total)
      meta.appendChild(metaSpan(fmtB(d.progress.done) + ' / ' + fmtB(d.progress.total)));
    else if (d.size)
      meta.appendChild(metaSpan(fmtB(d.size)));
    if (d.progress && d.progress.source)   // live download lane ("s3"/"github")
      meta.appendChild(metaSpan(fmtSrc(d.progress.source)));
    if (dateOf(d)) meta.appendChild(metaSpan(dateOf(d).slice(0, 10)));
    if (d.source) meta.appendChild(metaSpan(d.source));

    const actions = document.createElement('div');
    actions.className = 'pk-actions';
    const label = actionLabel(d);
    if (label) {
      const b = document.createElement('button');
      b.className = 'pri';
      b.textContent = label;
      b.addEventListener('click', (e) => { e.stopPropagation(); doDownload(d, b); });
      actions.appendChild(b);
    }
    if (liveDl.has(d.id)) {
      const b = document.createElement('button');
      b.className = 'pk-del';
      b.textContent = 'Cancel';
      b.addEventListener('click', (e) => { e.stopPropagation(); doCancel(d, b); });
      actions.appendChild(b);
    }
    if (d.state === 'ready' || d.state === 'failed') {
      const b = document.createElement('button');
      b.className = 'pk-del';
      b.textContent = 'Delete';
      b.addEventListener('click', (e) => { e.stopPropagation(); doDelete(d, b); });
      actions.appendChild(b);
    }

    row.append(main, badge, meta, actions);
    row.addEventListener('click', () => { opts.onSelect(d.id); close(); });
    return row;
  }

  const metaSpan = (text) => {
    const s = document.createElement('span');
    s.textContent = text;
    return s;
  };

  /* same rules as the old header button: remote -> download, failed -> retry
   * (the server maps both to start_download), busy without a live job ->
   * resume, indexing without a live job -> fill from a release / local build */
  const actionLabel = (d) => {
    if (d.state === 'remote') return 'Download';
    if (d.state === 'failed') return 'Retry download';
    if (liveDl.has(d.id)) return null;
    if (d.state === 'downloading' || d.state === 'assembling') return 'Resume download';
    if (d.state === 'indexing') return 'Fetch or build data';
    return null;
  };

  /* ---- per-row user tags ---- */

  function renderTagRow(d) {
    const wrap = document.createElement('div');
    wrap.className = 'pk-tags-row';
    const manual = manualTags(d);
    for (const t of allTags(d)) {
      const chip = document.createElement('span');
      chip.className = 'pk-tag' + (manual.includes(t) ? ' pk-tag-user' : '');
      const tx = document.createElement('span');
      tx.textContent = t;
      chip.appendChild(tx);
      if (manual.includes(t)) {
        const x = document.createElement('button');
        x.className = 'pk-x';
        x.textContent = '×';
        x.title = 'Remove tag';
        x.addEventListener('click', (e) => {
          e.stopPropagation();
          commitTags(d, manual.filter((m) => m !== t));
        });
        chip.appendChild(x);
      }
      wrap.appendChild(chip);
    }
    if (addingFor === d.id) {
      const inp = document.createElement('input');
      inp.type = 'text';
      inp.className = 'pk-addinput';
      inp.placeholder = 'tag, another tag…';
      inp.addEventListener('click', (e) => e.stopPropagation());
      inp.addEventListener('keydown', (e) => {
        e.stopPropagation();
        if (e.key === 'Enter') {
          commitTags(d, [...manual, ...inp.value.split(',')]);
        } else if (e.key === 'Escape') {
          addingFor = null;
          renderList();
        }
      });
      inp.addEventListener('blur', () => {
        if (addingFor === d.id) { addingFor = null; renderList(); }
      });
      wrap.appendChild(inp);
      setTimeout(() => inp.focus(), 0);
    } else {
      const add = document.createElement('button');
      add.className = 'pk-add';
      add.textContent = '+ tag';
      add.addEventListener('click', (e) => {
        e.stopPropagation();
        addingFor = d.id;
        renderList();
      });
      wrap.appendChild(add);
    }
    return wrap;
  }

  /* ---- ops (data layer; errors land in the sticky status line) ---- */

  const commitTags = async (d, tags) => {
    status = '';
    addingFor = null;
    const r = await setTags(d.id, tags.map((t) => t.trim()).filter(Boolean));
    if (!r.ok) status = `tags for ${d.id}: ${r.error}`;
    else d.meta = {...(d.meta || {}), tags: r.data.tags};   // local copy; boot re-lists on refresh
    render();
  };

  const doDownload = async (d, btn) => {
    status = '';
    btn.disabled = true;
    const r = await startDownload(d.id);
    if (!r.ok) { status = `${d.id}: ${r.error}`; btn.disabled = false; render(); }
    else opts.onRefresh();
  };

  const doCancel = async (d, btn) => {
    status = '';
    btn.disabled = true;
    const r = await cancelDownload(d.id);
    if (!r.ok) { status = `${d.id}: ${r.error}`; btn.disabled = false; render(); }
    else opts.onRefresh();
  };

  const doDelete = async (d, btn) => {
    if (!confirm(`Delete ${d.id}? The local dump and its indexes are removed.`)) return;
    status = '';
    btn.disabled = true;
    const r = await deleteDump(d.id);
    if (!r.ok) { status = `${d.id}: ${r.error}`; btn.disabled = false; render(); }
    else opts.onRefresh();
  };

  return {open, close, update, isOpen: () => openFlag};
}
