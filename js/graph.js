"use strict";
/* ============================== reference graph: class-level reference DAG over the
   union retained set. Nodes = classes, sized by RETAINED; layered topsorted layout
   (holders above, held below — edges point down), so shared classes show all their
   inbound connections. Value types (primitive arrays, String, Object[]) sit in a
   dedicated right lane: everything references them, they explain little, and their
   own outbound refs are not drawn (still listed in the click detail panel).
   Class-level cycles are real — A's instances hold B's and B's hold A's. Mutual
   pairs render as one two-way edge, self refs as a small arc, and the remaining
   cycle-closing edges as faint dashed lines (⟲ button toggles them). */
let GSTATE = {top: 140, hpm: null, hpu: null, hk: null, cyc: true};

function refgraphHTML(){
  const a = M.anat2;
  if(M.pend.anat2) return `<div class="loading"><span class="spinner"></span>loading reference graph…</div>`;
  if(!a) return `<div class="pad">No anatomy extracted for this class yet — the graph is built from the anatomy extraction.<br><br>
    Open the <b>Analyze</b> tab and run with anatomy enabled.</div>`;
  const maxN = a.graph.nodes.length, v = Math.min(GSTATE.top, maxN);
  const G = M.scale==="global", K = a.samples;
  return `<div style="display:flex;flex-direction:column;height:100%">
    <div style="display:flex;gap:14px;align-items:center;margin-bottom:6px;flex-wrap:wrap">
      <div class="anatseg" id="gseg">
        <button data-s="sample"${G?"":' class="on"'}>${K} sample instances</button>
        <button data-s="global"${G?' class="on"':""}>× ${fmtN(M.objCount)} instances (estimated)</button>
      </div>
      <span class="hint">top</span>
      <input type="range" id="gtop" min="30" max="${maxN}" value="${v}" style="width:110px">
      <span class="hint" id="gtopv">${v}</span>
      <span class="hint">scroll = pan · ctrl/shift+scroll = zoom (<b>+</b>/<b>−</b> keys, <b>0</b> = fit) · ring = shared (≥3 holders) · ⇄ = two-way ref · faint dashed = class-level cycle (⟲ toggles) · right lane = value types · zoom in for more labels · click = detail · double-click = open class</span></div>
    <div class="gwrap" style="flex:1;min-height:0"><div class="gcanvas-wrap"><svg id="gcanvas"></svg>
      <div class="gtools"><button id="gzout" title="zoom out (−)">−</button><button id="gzin" title="zoom in (+)">+</button><button id="gzfit" class="fit" title="fit to view (0)">fit</button></div>
    </div><div id="gside"></div></div></div>`;
}

function initGraph(){
  const svg = document.getElementById("gcanvas");
  if(!svg || !M.anat2 || !M.anat2.graph) return;
  const g = M.anat2.graph;
  const W = ()=>svg.clientWidth||760, H = ()=>svg.clientHeight||620;   // live: window may resize after init
  const shortC = c => c.split(".").pop();

  // ---- top-N classes by retained ----
  const topN = Math.min(GSTATE.top, g.nodes.length);
  const keepIdx = [...g.nodes.keys()].sort((i,j)=>g.nodes[j][3]-g.nodes[i][3]).slice(0, topN);
  const keep = new Set(keepIdx);
  const remap = new Map(keepIdx.map((oi,i)=>[oi,i]));
  const N = keepIdx.map(oi=>({oi, cls:g.nodes[oi][0], n:g.nodes[oi][1], s:g.nodes[oi][2], r:g.nodes[oi][3]}));
  const inL = N.map(()=>[]), outL = N.map(()=>[]);
  const pmap = new Map();
  for(const l of g.links){
    if(!keep.has(l[0]) || !keep.has(l[1])) continue;
    const s = remap.get(l[0]), t = remap.get(l[1]);
    const e = {s,t,f:l[2],n:l[3],b:l[4]};
    inL[t].push(e); outL[s].push(e);
    if(s===t) continue;
    const k = s+"|"+t;
    let p = pmap.get(k);
    if(!p){ p = {s,t,b:0,n:0}; pmap.set(k,p); }
    p.b += l[4]; p.n += l[3];
  }
  const D = [...pmap.values()];   // directed pair edges (fields summed, self-loops excluded)
  const pIn = N.map(()=>[]), pOut = N.map(()=>[]);
  D.forEach((e,ei)=>{ pOut[e.s].push(ei); pIn[e.t].push(ei); });

  // ---- cycle break (DFS from biggest: edges to on-stack nodes are back edges) ----
  const color = N.map(()=>0), back = new Set();
  const starts = [...N.keys()].sort((a,b)=>N[b].r-N[a].r);
  for(const s0 of starts){
    if(color[s0]) continue;
    color[s0]=1;
    const st=[[s0,0]];
    while(st.length){
      const top = st[st.length-1], u = top[0];
      if(top[1] < pOut[u].length){
        const ei = pOut[u][top[1]++], v = D[ei].t;
        if(color[v]===0){ color[v]=1; st.push([v,0]); }
        else if(color[v]===1) back.add(ei);
      } else { color[u]=2; st.pop(); }
    }
  }

  // ---- merge directed pairs into render edges: A⇄B collapses to one two-way edge ----
  const dirIdx = new Map(D.map((e,ei)=>[e.s+"|"+e.t, ei]));
  const R = [], seenPair = new Set();
  for(let ei=0; ei<D.length; ei++){
    const e = D[ei], ri = dirIdx.get(e.t+"|"+e.s);
    const pk = Math.min(e.s,e.t)+"|"+Math.max(e.s,e.t);
    if(seenPair.has(pk)) continue;
    if(ri!==undefined){   // two-way class-level reference
      seenPair.add(pk);
      const o = D[ri], aB = back.has(ei), bB = back.has(ri);
      const fwd = (aB&&!bB) ? o : (bB&&!aB) ? e : (e.b>=o.b ? e : o);
      R.push({s:fwd.s, t:fwd.t, bi:true, b:Math.max(e.b,o.b), n:e.n+o.n,
        tip:`${shortC(N[e.s].cls)} → ${shortC(N[e.t].cls)}: ×${fmtN(e.n)}, ${fmtB(e.b)}\n`+
            `${shortC(N[e.t].cls)} → ${shortC(N[e.s].cls)}: ×${fmtN(o.n)}, ${fmtB(o.b)}`});
    } else {
      R.push({s:e.s, t:e.t, bi:false, b:e.b, n:e.n, cyc:back.has(ei),
        tip:`${shortC(N[e.s].cls)} → ${shortC(N[e.t].cls)}: ×${fmtN(e.n)}, ${fmtB(e.b)}`+
            (back.has(ei)?"\nclass-level cycle edge (points up)":"")});
    }
  }

  // ---- longest-path layering (ignoring back edges) ----
  const indeg = N.map(()=>0);
  D.forEach((e,ei)=>{ if(!back.has(ei)) indeg[e.t]++; });
  const layer = N.map(()=>0);
  const q = [], seen = new Set();
  indeg.forEach((d,i)=>{ if(d===0) q.push(i); });
  while(q.length){
    const u = q.shift(); seen.add(u);
    for(const ei of pOut[u]){
      if(back.has(ei)) continue;
      const v = D[ei].t;
      layer[v] = Math.max(layer[v], layer[u]+1);
      if(--indeg[v]===0) q.push(v);
    }
  }
  for(let i=0;i<N.length;i++) if(!seen.has(i)) layer[i]=0;   // pure-cycle leftovers

  // ---- value lane: primitive arrays / String / Object[] are pulled out AFTER layout
  //      (their edges still layer their holders/held, so nothing floats to the top) ----
  const LANE_RE = /^(byte|char|short|int|long|float|double|boolean)\[\]$/;
  const laneU = new Set();
  N.forEach((nd,u)=>{
    if(nd.cls===M.full) return;
    if(LANE_RE.test(nd.cls) || nd.cls==="java.lang.String" || nd.cls==="java.lang.Object[]") laneU.add(u);
  });

  // ---- order within layers: barycenter sweeps (lane nodes included, then removed) ----
  const layersA = [];
  for(let i=0;i<N.length;i++){ (layersA[layer[i]] = layersA[layer[i]]||[]).push(i); }
  for(const ly of layersA) if(ly) ly.sort((a,b)=>N[b].r-N[a].r);
  const positions = ()=>{ const p = N.map(()=>0); layersA.forEach(ly=>ly&&ly.forEach((u,i)=>p[u]=i)); return p; };
  const bc = (u, pos, up)=>{
    const rel = up ? pOut[u].filter(ei=>!back.has(ei)).map(ei=>pos[D[ei].t])
                   : pIn[u].filter(ei=>!back.has(ei)).map(ei=>pos[D[ei].s]);
    return rel.length ? rel.reduce((a,b)=>a+b,0)/rel.length : -1;
  };
  for(let it=0; it<8; it++){
    let pos = positions();
    for(let d=1; d<layersA.length; d++)
      if(layersA[d]) layersA[d].sort((a,b)=>{ const x=bc(a,pos,false), y=bc(b,pos,false);
        return (x<0?Infinity:x)-(y<0?Infinity:y) || N[b].r-N[a].r; });
    pos = positions();
    for(let d=layersA.length-2; d>=0; d--)
      if(layersA[d]) layersA[d].sort((a,b)=>{ const x=bc(a,pos,true), y=bc(b,pos,true);
        return (x<0?Infinity:x)-(y<0?Infinity:y) || N[b].r-N[a].r; });
  }
  const Ls = [];
  for(const ly of layersA){ if(!ly) continue; const f = ly.filter(u=>!laneU.has(u)); if(f.length) Ls.push(f); }

  // ---- coordinates ----
  const rmax = Math.max(...N.map(d=>d.r), 1);
  const rad = d => 5 + 15*Math.sqrt(d.r/rmax);
  const LAYERH = 78, SPX = 26;
  const lw = Ls.map(ly=>ly.reduce((a,u)=>a+2*rad(N[u])+SPX,-SPX));
  const maxW = Math.max(...lw, 0);
  const totalH = Ls.length*LAYERH;
  const xy = N.map(()=>({x:0,y:0}));
  Ls.forEach((ly,d)=>{
    let x = (maxW-lw[d])/2;
    for(const u of ly){ x += rad(N[u]); xy[u]={x, y:d*LAYERH+LAYERH/2}; x += rad(N[u])+SPX; }
  });
  const laneList = [...laneU].sort((a,b)=>N[b].r-N[a].r);
  const laneX = maxW + 130;
  laneList.forEach((u,i)=>{ xy[u] = {x:laneX, y: totalH*(i+0.5)/Math.max(1,laneList.length)}; });
  const laneR = laneList.length ? Math.max(...laneList.map(u=>rad(N[u]))) : 0;
  const totalW = laneList.length ? laneX + laneR + 12 : maxW;

  // ---- render (into a pannable/zoomable viewport group) ----
  svg.innerHTML = "";
  const defs = document.createElementNS(NS,"defs");
  svg.appendChild(defs);
  // fixed-size arrowheads (userSpaceOnUse — they must NOT scale with edge width)
  for(const [id,color] of [["arr","#4a5568"],["arr-in","#f0883e"],["arr-out","#79b8ff"],["arr-bi","#9b7ede"]]){
    const m = document.createElementNS(NS,"marker");
    m.setAttribute("id",id); m.setAttribute("viewBox","0 0 8 8");
    m.setAttribute("refX",7); m.setAttribute("refY",4);
    m.setAttribute("markerWidth",7); m.setAttribute("markerHeight",7);
    m.setAttribute("markerUnits","userSpaceOnUse");
    m.setAttribute("orient","auto-start-reverse");
    const p = document.createElementNS(NS,"path");
    p.setAttribute("d","M 0 0 L 8 4 L 0 8 z");
    p.setAttribute("fill",color);
    m.appendChild(p); defs.appendChild(m);
  }
  const vp = document.createElementNS(NS,"g");
  svg.appendChild(vp);
  const mk = (t,at)=>{ const e=document.createElementNS(NS,t); for(const k in at) e.setAttribute(k,at[k]); vp.appendChild(e); return e; };
  const emax = Math.max(...R.map(e=>e.b), 1);
  const ew = e => 0.7 + 3.3*Math.sqrt(e.b/emax);
  const setMarkers = (el,e,id)=>{
    if(e.aT) el.setAttribute("marker-end",`url(#${id})`); else el.removeAttribute("marker-end");
    if(e.aS) el.setAttribute("marker-start",`url(#${id})`); else el.removeAttribute("marker-start");
  };
  const cycIdx = [];
  const edgeEls = R.map((e,ri)=>{
    const laneS = laneU.has(e.s), laneT = laneU.has(e.t);
    if((laneS && laneT) || (laneS && !e.bi)) return null;   // lane-internal / lane outbound: not drawn
    let gs = e.s, gt = e.t;
    if(laneS){ gs = e.t; gt = e.s; }                          // draw main → lane
    e.aT = (gt===e.t) || e.bi;   // arrowhead at an end iff a directed edge points into it
    e.aS = (gs===e.s) ? e.bi : true;
    const a = xy[gs], b = xy[gt], rs = rad(N[gs]), rt = rad(N[gt]);
    let d;
    if(!laneS && !laneT && !e.cyc){
      const y1 = a.y+rs+1, y2 = b.y-rt-1, my = (y1+y2)/2;
      d = `M ${a.x} ${y1} C ${a.x} ${my}, ${b.x} ${my}, ${b.x} ${y2}`;
    } else if(laneT){
      const y1 = a.y+rs+1, mx = (a.x+b.x)/2;
      d = `M ${a.x} ${y1} C ${mx} ${y1}, ${mx} ${b.y}, ${b.x-rt-1} ${b.y}`;
    } else {   // cycle back-edge: route around the right of both nodes
      const off = 20 + (ri%4)*9, mx = Math.max(a.x, b.x) + off;
      d = `M ${a.x} ${a.y-rs} C ${mx} ${a.y-34}, ${mx} ${b.y+34}, ${b.x} ${b.y+rt}`;
    }
    if(e.cyc) cycIdx.push(ri);
    const el = mk("path", {d, fill:"none", stroke:"#4a5568",
      "stroke-width":(e.cyc?1:ew(e)).toFixed(2), opacity:e.cyc?0.15:0.5,
      ...(e.cyc?{"stroke-dasharray":"3 4"}:{})});
    setMarkers(el,e,"arr");
    const ti = document.createElementNS(NS,"title");
    ti.textContent = e.tip; el.appendChild(ti);
    return el;
  });
  // lane separator + caption
  if(laneList.length){
    mk("line", {x1:laneX-75, y1:-8, x2:laneX-75, y2:totalH+8, stroke:"#232b38", "stroke-width":1, "stroke-dasharray":"2 5"});
    const cap = mk("text", {x:laneX, y:-2, "text-anchor":"middle", "class":"glabel"});
    cap.textContent = "value types";
  }
  // self-loops: arc hugging the node's top-right edge (not a detached floating ring)
  N.forEach((nd,u)=>{
    if(!inL[u].some(l=>l.s===l.t)) return;
    mk("circle", {cx:xy[u].x+rad(nd)*0.72, cy:xy[u].y-rad(nd)*0.72, r:Math.max(3, rad(nd)*0.3),
      fill:"none", stroke:"#3c4756", "stroke-width":0.9, opacity:0.85});
  });
  // shared ring: held by ≥3 classes — the ones you shrink, not free
  N.forEach((nd,u)=>{
    if(inL[u].filter(l=>l.s!==l.t).length >= 3)
      mk("circle", {cx:xy[u].x, cy:xy[u].y, r:rad(nd)+2.6,
        fill:"none", stroke:"#9b7ede", "stroke-width":1.2, opacity:0.8});
  });
  const circles = N.map((nd,u)=>{
    return mk("circle", {cx:xy[u].x, cy:xy[u].y, r:rad(nd),
      fill:catColor(catOfName(nd.cls)), "fill-opacity":0.85, stroke:"#0d1117","stroke-width":0.8,
      style:"cursor:pointer", ...(nd.r < rmax*0.005 ? {opacity:0.5} : {})});
  });

  // ---- labels: screen-space overlay, measured, collision-free, zoom-revealed ----
  const labG = document.createElementNS(NS,"g");
  svg.appendChild(labG);
  const mctx = document.createElement("canvas").getContext("2d");
  mctx.font = "9.5px system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif";
  const wcache = new Map();
  const tw = s=>{ let w = wcache.get(s); if(w===undefined){ w = mctx.measureText(s).width; wcache.set(s,w); } return w; };
  const rankOrd = [...N.keys()].sort((a,b)=>N[b].r-N[a].r);
  const rankOf = new Map(rankOrd.map((u,i)=>[u,i]));
  let zm, labRAF = 0;
  const drawLabels = ()=>{
    labRAF = 0;
    labG.textContent = "";
    const placed = [];
    for(const u of rankOrd){
      const nd = N[u], p = xy[u];
      const sx = p.x*zm.k+zm.x, sy = p.y*zm.k+zm.y, sr = rad(nd)*zm.k;
      if(sx<-60 || sx>W()+60 || sy<-24 || sy>H()+24) continue;
      if(sr<5 && rankOf.get(u)>=28) continue;      // small nodes label themselves when zoomed in
      let name = shortC(nd.cls); if(name.length>30) name = name.slice(0,29)+"…";
      const w = tw(name)+2;
      for(const c of [{x:sx+sr+5, y:sy+3.5, a:"start"}, {x:sx-sr-5, y:sy+3.5, a:"end"}, {x:sx, y:sy+sr+12, a:"middle"}]){
        const x0 = c.a==="start" ? c.x : c.a==="end" ? c.x-w : c.x-w/2;
        if(x0<2 || x0+w>W()-2) continue;
        const r = {x0, y0:c.y-9, x1:x0+w, y1:c.y+2.5};
        if(placed.some(q=>r.x0<q.x1 && q.x0<r.x1 && r.y0<q.y1 && q.y0<r.y1)) continue;
        placed.push(r);
        const t = document.createElementNS(NS,"text");
        t.setAttribute("x",c.x); t.setAttribute("y",c.y);
        t.setAttribute("text-anchor",c.a); t.setAttribute("class","glabel");
        t.textContent = name; labG.appendChild(t);
        break;
      }
    }
  };
  const queueLabels = ()=>{ if(!labRAF) labRAF = requestAnimationFrame(drawLabels); };

  // ---- viewport transform: scroll pans, ctrl/shift+scroll zooms, keys +/- and 0 ----
  const apply = ()=>{ vp.setAttribute("transform", `translate(${zm.x},${zm.y}) scale(${zm.k})`); queueLabels(); };
  const fit = ()=>{
    zm = {k: Math.min(1, (W()-40)/Math.max(1,totalW)), x: 0, y: 14};
    zm.x = (W() - totalW*zm.k)/2;
    apply();
  };
  const zoomAt = (mx, my, dk)=>{
    const k2 = Math.min(10, Math.max(0.1, zm.k*dk));
    zm.x = mx-(mx-zm.x)*(k2/zm.k); zm.y = my-(my-zm.y)*(k2/zm.k); zm.k = k2;
    apply();
  };
  fit();
  svg.addEventListener("wheel", e=>{
    e.preventDefault();
    if(e.ctrlKey || e.shiftKey){
      const rc = svg.getBoundingClientRect();
      zoomAt(e.clientX-rc.left, e.clientY-rc.top, e.deltaY<0 ? 1.18 : 1/1.18);
    } else {
      zm.x -= e.deltaX; zm.y -= e.deltaY;
      apply();
    }
  }, {passive:false});
  // drag pan: window-level tracking, no pointer capture (that killed node clicks)
  let drag = null, moved = false;
  svg.addEventListener("pointerdown", e=>{
    drag = {x:e.clientX, y:e.clientY, zx:zm.x, zy:zm.y};
    moved = false;
  });
  if(GSTATE.hpm) window.removeEventListener("pointermove", GSTATE.hpm);
  if(GSTATE.hpu) window.removeEventListener("pointerup", GSTATE.hpu);
  if(GSTATE.hk) window.removeEventListener("keydown", GSTATE.hk);
  window.addEventListener("pointermove", GSTATE.hpm = e=>{
    if(!drag) return;
    const dx = e.clientX-drag.x, dy = e.clientY-drag.y;
    if(!moved && Math.abs(dx)+Math.abs(dy) > 5){ moved = true; svg.classList.add("panning"); }
    if(moved){ zm.x = drag.zx+dx; zm.y = drag.zy+dy; apply(); }
  });
  window.addEventListener("pointerup", GSTATE.hpu = ()=>{ drag = null; svg.classList.remove("panning"); });
  window.addEventListener("keydown", GSTATE.hk = e=>{
    if(!document.body.contains(svg) || !backdrop.classList.contains("open") || M.tab!=="graph") return;
    if(e.key==="+"||e.key==="=") zoomAt(W()/2, H()/2, 1.25);
    else if(e.key==="-"||e.key==="_") zoomAt(W()/2, H()/2, 0.8);
    else if(e.key==="0") fit();
  });
  document.getElementById("gzin").onclick = ()=>zoomAt(W()/2, H()/2, 1.25);
  document.getElementById("gzout").onclick = ()=>zoomAt(W()/2, H()/2, 0.8);
  document.getElementById("gzfit").onclick = fit;
  // cycle-edge toggle
  if(cycIdx.length){
    const b = document.createElement("button");
    b.className = "fit"; b.id = "gcyc";
    b.textContent = "⟲ "+cycIdx.length;
    b.title = "class-level cycle edges (A holds B and B holds A at class granularity) — click to hide/show";
    const setCyc = on=>cycIdx.forEach(ri=>{ const el = edgeEls[ri]; if(el) el.style.display = on?"":"none"; });
    b.onclick = ()=>{ GSTATE.cyc = !GSTATE.cyc; setCyc(GSTATE.cyc); b.style.opacity = GSTATE.cyc?1:0.45; };
    if(!GSTATE.cyc){ setCyc(false); b.style.opacity = 0.45; }
    document.querySelector(".gtools").appendChild(b);
  }

  // ---- node inspection ----
  const G = M.scale==="global";
  const Mf = G ? Math.max(1, M.objCount)/M.anat2.samples : 1;
  const fxB = v => G ? "≈ "+fmtB(v*Mf) : fmtB(v);
  const fxN = v => G ? "≈ "+fmtN(v*Mf) : fmtN(v);
  const side = document.getElementById("gside");
  const defaultSide = `<div style="color:var(--dim)">${N.length} classes (top by retained) · ${R.length} connections · ${Ls.length} layers`+
    `${laneList.length?` · ${laneList.length} value types in the right lane`:""}${cycIdx.length?` · ${cycIdx.length} cycle edges`:""}`+
    `${G?` · numbers extrapolated per-instance × ${fmtN(M.objCount)}`:""}.<br><br>
    <b>Read it:</b> top = the inspected class; each layer is what the layers above hold.
    <b>Node size = the class's TOTAL retained bytes in the whole retained set</b> — a child can be
    bigger than its parent: that means it is held by many others (shared). Those are the ones to
    shrink; big nodes on thin/few inbound edges are candidates for being freed outright.
    Purple ring = shared (≥3 holder classes). ⇄ = two-way class-level reference; ↻ arc = self-reference.
    Right lane = value types (primitive arrays, String, Object[]) — everything points to them and they
    explain little; their own outbound refs are not drawn (click one to see them listed).
    Faint dashed = class-level cycle edge — a real reference that closes a cycle; hide it with the ⟲ button.<br><br>
    Click a node for its holder/held breakdown.</div>`;
  const resetEdges = ()=>edgeEls.forEach((el,ri)=>{
    if(!el) return;
    const e = R[ri];
    el.setAttribute("stroke", "#4a5568");
    el.setAttribute("opacity", e.cyc?0.15:0.5);
    el.setAttribute("stroke-width", (e.cyc?1:ew(e)).toFixed(2));
    setMarkers(el,e,"arr");
  });
  side.innerHTML = defaultSide;
  const showNode = u => {
    const nd = N[u];
    const row = (l,o)=>`<tr><td title="${esc(o.cls)}">${esc(shortC(o.cls)).replace(/\$/g,"$\u200b").replace(/([a-z])([A-Z])/g,"$1\u200b$2")}</td><td class="num" title="${esc(l.f)}">${esc(l.f==="[]"?"[]":l.f.length>16?l.f.slice(0,16)+"…":l.f)}</td><td class="num">×${fmtN(l.n)}</td><td class="num">${fxB(l.b)}</td></tr>`;
    const ins = inL[u].filter(l=>l.s!==l.t).sort((a,b)=>b.b-a.b).slice(0,14);
    const outs = outL[u].filter(l=>l.s!==l.t).sort((a,b)=>b.b-a.b).slice(0,14);
    side.innerHTML = `<h5>${esc(shortC(nd.cls))}</h5>
      <div style="color:var(--dim);word-break:break-all;font-size:10px">${esc(nd.cls)}</div>
      <div style="margin-top:3px">retained <b>${fxB(nd.r)}</b> · shallow ${fxB(nd.s)} · ${fxN(nd.n)} objects${G?' <span class="hint">(est.)</span>':""}</div>
      <div style="margin-top:3px;color:var(--dim)">${laneU.has(u)?"value lane · outbound refs listed, not drawn · ":""}layer ${layer[u]} · held by ${ins.length} class${ins.length!==1?"es":""} · holds ${outs.length}</div>
      <div style="margin-top:7px"><button class="pri" id="gopen" style="font-size:11px;padding:3px 10px">open class ▸</button></div>
      <div class="glab">held by (inbound refs — shared when many)</div><table>${ins.map(l=>row(l,N[l.s])).join("")||"<tr><td>—</td></tr>"}</table>
      <div class="glab">holds (outbound refs)</div><table>${outs.map(l=>row(l,N[l.t])).join("")||"<tr><td>—</td></tr>"}</table>`;
    side.querySelector("#gopen").onclick = ()=>openModal(nd.cls, {c:nd.n, s:nd.s});
    edgeEls.forEach((el,ri)=>{
      if(!el) return;
      const e = R[ri], on = e.s===u||e.t===u;
      if(!on){ el.setAttribute("stroke", "#4a5568"); el.setAttribute("opacity", e.cyc?0.06:0.08);
        el.setAttribute("stroke-width", (e.cyc?1:ew(e)).toFixed(2)); setMarkers(el,e,"arr"); return; }
      const kind = e.bi ? "bi" : (e.t===u ? "in" : "out");
      el.setAttribute("stroke", kind==="in"?"#f0883e":kind==="out"?"#79b8ff":"#9b7ede");
      el.setAttribute("opacity", 0.95);
      el.setAttribute("stroke-width", (1.2+3.3*Math.sqrt(e.b/emax)).toFixed(2));
      setMarkers(el,e,"arr-"+kind);
    });
  };
  circles.forEach((c,u)=>{
    c.addEventListener("click", e=>{ if(!moved){ showNode(u); e.stopPropagation(); } });
    c.addEventListener("dblclick", e=>{ openModal(N[u].cls, {c:N[u].n, s:N[u].s}); e.stopPropagation(); });
  });
  svg.addEventListener("click", e=>{
    if(e.target===svg && !moved){ resetEdges(); side.innerHTML = defaultSide; }
  });
}
