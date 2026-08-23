/* Treemap tab — module/package treemap from trees(id): metric segment control,
   click-to-zoom with breadcrumbs, hover tooltip, category legend. */
import {esc, fmtB, fmtN} from '../../data/http.js';
import * as ddr from '../../data/dumpdatarepo.js';
import {getDump, onDumpChange} from '../../app/state.js';
import {openViz} from '../../viz/common.js';
import {catCls, dumpNotReady} from './classes.js';
import {catOf} from '../../data/http.js';

const NS = 'http://www.w3.org/2000/svg';
const CAT_HEX = {gradle: '#e8743b', agp: '#3ba272', kotlin: '#9b7ede', jdk: '#4a90d9', other: '#7d8590'};
const METRICS = [['r', 'Retained heap'], ['s', 'Shallow heap'], ['c', 'Instance count']];

export function nodeValue(n, m){
  return n.leaf ? (n[m] || 0) : (n.children || []).reduce((a, c) => a + nodeValue(c, m), 0);
}
export function nodeCount(n){
  return n.leaf ? (n.c || 0) : (n.children || []).reduce((a, c) => a + nodeCount(c), 0);
}

/* Squarified layout: returns [{n, v, x, y, w, h}] for children with value > 0. */
export function squarify(children, x, y, w, h, valueOf){
  const out = [];
  const items = children.map(c => ({n: c, v: valueOf(c)})).filter(d => d.v > 0).sort((a, b) => b.v - a.v);
  const total = items.reduce((a, d) => a + d.v, 0);
  if(total <= 0 || w <= 0 || h <= 0) return out;
  const scale = w * h / total;
  let row = [], rowSum = 0, cx = x, cy = y, cw = w, ch = h;
  const worst = (sum, len) => {
    let mx = 0, mn = Infinity;
    for(const d of row){ mx = Math.max(mx, d.v); mn = Math.min(mn, d.v); }
    return Math.max((len * len * mx) / (sum * sum), (sum * sum) / (len * len * mn));
  };
  const layoutRow = () => {
    const vert = cw >= ch, len = vert ? ch : cw, thick = rowSum * scale / len;
    let off = vert ? cy : cx;
    for(const d of row){
      const l = d.v * scale / thick;
      out.push(vert ? {n: d.n, v: d.v, x: cx, y: off, w: thick, h: l}
                    : {n: d.n, v: d.v, x: off, y: cy, w: l, h: thick});
      off += l;
    }
    if(vert){ cx += thick; cw -= thick; } else { cy += thick; ch -= thick; }
    row = []; rowSum = 0;
  };
  for(const d of items){
    const len = Math.min(cw, ch);
    if(row.length && worst(rowSum, len) < worst(rowSum + d.v, len)) layoutRow();
    row.push(d); rowSum += d.v;
  }
  if(row.length) layoutRow();
  return out;
}

const shadeCache = new Map();
/* Deterministic per-name shade of a category color. */
export function shade(hex, seed){
  const key = hex + seed;
  if(shadeCache.has(key)) return shadeCache.get(key);
  let h = 0;
  for(let i = 0; i < seed.length; i++) h = (h * 31 + seed.charCodeAt(i)) >>> 0;
  const f = 0.82 + (h % 100) / 100 * 0.36;
  const c = hex.match(/\w\w/g).map(v => Math.min(255, Math.round(parseInt(v, 16) * f)));
  const rgb = `rgb(${c[0]},${c[1]},${c[2]})`;
  shadeCache.set(key, rgb);
  return rgb;
}

export function mount(container, repo, opts = {}){
  const R = repo || ddr;                 // INLINE mode passes makeInlineRepo()
  const st = {metric: 'r', zoomPath: [], stats: null, data: null, dump: null, seq: 0};

  /* ---- skeleton ---- */
  const card = document.createElement('div'); card.className = 'card';
  const controls = document.createElement('div'); controls.className = 'controls';
  const seg = document.createElement('div'); seg.className = 'seg';
  const segBtns = [];
  for(const [m, label] of METRICS){
    const b = document.createElement('button');
    b.textContent = label;
    b.dataset.m = m;
    if(m === st.metric) b.classList.add('on');
    b.addEventListener('click', () => {
      st.metric = m; st.zoomPath = [];
      for(const x of segBtns) x.classList.toggle('on', x === b);
      render();
    });
    segBtns.push(b); seg.appendChild(b);
  }
  const hint = document.createElement('span'); hint.className = 'hint';
  hint.textContent = 'retained view = top-level dominators only (MAT export): objects dominated by another ' +
    'object roll up into their owner. The complete per-class picture is in the Classes tab.';
  controls.appendChild(seg); controls.appendChild(hint);
  const crumbs = document.createElement('div'); crumbs.className = 'crumbs';
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('class', 'tmap');
  const legend = document.createElement('div'); legend.className = 'legend';
  card.appendChild(controls); card.appendChild(crumbs); card.appendChild(svg); card.appendChild(legend);
  const msg = document.createElement('div'); msg.className = 'tabmsg';
  const tip = document.createElement('div'); tip.className = 'tip';
  container.appendChild(card); container.appendChild(msg); container.appendChild(tip);

  function showMsg(text, isErr){
    msg.textContent = text;
    msg.className = 'tabmsg on' + (isErr ? ' err' : '');
    card.classList.add('hidden');
  }
  function hideMsg(){
    msg.className = 'tabmsg';
    card.classList.remove('hidden');
  }

  /* ---- data ---- */
  async function load(){
    const my = ++st.seq;
    const id = getDump();
    st.dump = id; st.zoomPath = [];
    if(!id){ showMsg('no dump selected — pick a dump in the selector above.'); return; }
    showMsg('loading…');
    const nr = opts.inline ? null : await dumpNotReady(id);   // snapshots are READY by construction
    if(my !== st.seq) return;
    if(nr){ showMsg(nr.msg, nr.err); return; }
    const r = await R.trees(id);
    if(my !== st.seq) return;
    if(!r.ok){
      showMsg(`${id}: ${r.error || 'failed to load trees'}`, true);
      // busy dump, data bundle not unpacked yet — retry until it lands (the
      // retry re-checks dumpNotReady, so failed/remote dumps terminate here)
      if(r.status === 409 && !opts.inline)
        setTimeout(() => { if(st.seq === my && st.dump === getDump()) load(); }, 4000);
      return;
    }
    st.stats = r.data.stats || {};
    st.data = r.data.trees || null;
    hideMsg();
    render();
  }

  function baseTree(){ return st.metric === 'r' ? (st.data || {}).dom : (st.data || {}).hist; }
  function totalFor(){
    return st.metric === 'r' ? st.stats.totalRetained
         : st.metric === 's' ? st.stats.totalShallow : st.stats.totalObjects;
  }
  function treeRoot(){
    const base = baseTree();
    let n = base;
    for(const name of st.zoomPath){
      const k = ((n && n.children) || []).find(c => c.name === name);
      if(!k){ st.zoomPath = []; return base; }
      n = k;
    }
    return n;
  }
  function catOfNode(node){ return node.cat || catOf(node.disp || node.name || ''); }

  /* ---- render ---- */
  function render(){
    if(!st.data) return;
    /* Measure the svg itself: the pane is display:none while another tab is
       active (clientWidth 0 → skip; the ResizeObserver refires when the pane
       becomes visible again). The viewBox must match the CSS box exactly —
       a stale width would letterbox the whole map into a small centered strip. */
    const W = Math.round(svg.clientWidth);
    if(!W) return;
    const H = Math.round(svg.clientHeight) || 620;
    const root = treeRoot();
    svg.setAttribute('viewBox', `0 0 ${W} ${H}`);
    svg.innerHTML = '';
    const kids = (root && root.children) || [];
    const total = kids.reduce((a, c) => a + nodeValue(c, st.metric), 0);
    renderCrumbs();
    if(total <= 0){
      legend.innerHTML = '';
      showMsg('no tree data for this metric in this dump.');
      return;
    }
    hideMsg();
    for(const b of squarify(kids, 0, 0, W, H, c => nodeValue(c, st.metric))){
      const node = b.n;
      const g = document.createElementNS(NS, 'g');
      if(node.leaf) g.setAttribute('class', 'leaf');
      const r = document.createElementNS(NS, 'rect');
      r.setAttribute('class', 'cell');
      r.setAttribute('x', b.x); r.setAttribute('y', b.y);
      r.setAttribute('width', Math.max(0, b.w - 0.5));
      r.setAttribute('height', Math.max(0, b.h - 0.5));
      r.setAttribute('fill', shade(CAT_HEX[catOfNode(node)] || CAT_HEX.other, node.name || ''));
      r.setAttribute('rx', 2);
      g.appendChild(r);
      if(b.w > 52 && b.h > 18){
        const t = document.createElementNS(NS, 'text');
        t.setAttribute('x', b.x + 6); t.setAttribute('y', b.y + 17);
        t.setAttribute('font-size', '13'); t.setAttribute('opacity', '.92');
        const maxCh = Math.floor((b.w - 12) / 7.8);
        let label = String(node.name || '').split('.').pop();
        if(label.length > maxCh) label = label.slice(0, Math.max(1, maxCh - 1)) + '…';
        t.textContent = label;
        g.appendChild(t);
        if(b.h > 36){
          const v = document.createElementNS(NS, 'text');
          v.setAttribute('x', b.x + 6); v.setAttribute('y', b.y + 32);
          v.setAttribute('font-size', '11.5'); v.setAttribute('opacity', '.65');
          v.textContent = st.metric === 'c' ? fmtN(nodeValue(node, 'c')) : fmtB(nodeValue(node, st.metric));
          g.appendChild(v);
        }
      }
      g.addEventListener('mousemove', e => showTip(e, node));
      g.addEventListener('mouseleave', () => tip.classList.remove('on'));
      if(!node.leaf) g.addEventListener('click', () => { st.zoomPath.push(node.name); render(); });
      else g.addEventListener('click', () => openViz('anatomy', st.dump, node.disp || node.name));
      svg.appendChild(g);
    }
    renderLegend();
  }

  function renderCrumbs(){
    crumbs.innerHTML = '';
    const add = (label, i) => {
      const a = document.createElement('a');
      a.textContent = label;   // textContent — data-derived names are never innerHTML'd
      a.addEventListener('click', () => { st.zoomPath = st.zoomPath.slice(0, i); render(); });
      crumbs.appendChild(a);
    };
    add('all', 0);
    st.zoomPath.forEach((p, i) => {
      crumbs.appendChild(document.createTextNode(' › '));
      add(p, i + 1);
    });
  }

  function renderLegend(){
    legend.innerHTML = '';
    const base = baseTree();
    const tot = totalFor() || 1;
    for(const c of ((base && base.children) || [])){
      const v = nodeValue(c, st.metric);
      const s = document.createElement('span');
      const i = document.createElement('i');
      i.className = 'csw ' + catCls(c.cat);
      s.appendChild(i);
      s.appendChild(document.createTextNode(
        `${c.name} — ${st.metric === 'c' ? fmtN(v) : fmtB(v)} (${(100 * v / tot).toFixed(1)}%)`));
      s.addEventListener('click', () => { st.zoomPath = [c.name]; render(); });
      legend.appendChild(s);
    }
  }

  function showTip(e, node){
    const v = nodeValue(node, st.metric);
    const tot = totalFor() || 1;
    const mods = st.stats.modules || 1;
    const pct = (100 * v / tot).toFixed(2);
    tip.innerHTML = `<div class="t-name">${esc(node.disp || node.name || '')}</div><table>
      <tr><td>${st.metric === 'c' ? 'instances' : st.metric === 's' ? 'shallow heap' : 'retained heap'}</td><td class="v">${st.metric === 'c' ? fmtN(v) : fmtB(v)}</td><td class="v">${pct}%</td></tr>
      ${node.leaf
        ? `<tr><td>instances</td><td class="v">${fmtN(node.c || 0)}</td><td class="v">${((node.c || 0) / mods).toFixed(1)}/module</td></tr>`
        : `<tr><td>instances</td><td class="v">${fmtN(nodeCount(node))}</td><td class="v"></td></tr>`}
      ${st.metric !== 'c' ? `<tr><td>per module</td><td class="v">${fmtB(v / mods)}</td><td class="v"></td></tr>` : ''}
      ${node.leaf && node.r ? `<tr><td>per instance</td><td class="v">${fmtB(node.r / Math.max(1, node.c || 1))}</td><td class="v"></td></tr>` : ''}
    </table>${node.leaf
      ? `<div class="t-act">click — open class ▸</div>`
      : `<div class="t-zoom">click to zoom</div>`}`;
    tip.classList.add('on');
    const vw = document.documentElement.clientWidth || 1200;
    const vh = document.documentElement.clientHeight || 800;
    tip.style.left = Math.min(e.clientX + 14, vw - tip.offsetWidth - 10) + 'px';
    tip.style.top = Math.min(e.clientY + 14, vh - tip.offsetHeight - 10) + 'px';
  }

  /* ---- wiring ---- */
  /* ResizeObserver (not window resize): fires for window resizes AND for the
     pane becoming visible again after another tab was active (0 → real size). */
  new ResizeObserver(() => { if(st.data) render(); }).observe(svg);
  onDumpChange(load);
  load();
}
