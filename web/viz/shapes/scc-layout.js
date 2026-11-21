/* Pure layout for the object graph inside one SCC. Each box is a WL quotient
   group (count structurally identical members, labelled ×n); boxes use
   canonical artifact order initially, then barycenter sweeps over a
   deterministic DFS cycle break. Every unique group-to-group connection is
   retained; the edge multiplicity is per member of the source group. No DOM
   or recursion. */

const MEMBER_H = 20;
const MEMBER_MIN_W = 44;
const MEMBER_MAX_W = 174;
const X_GAP = 18;
const ROW_GAP = 18;
const LAYER_GAP = 42;
const PADDING = 20;
const HEADER_H = 24;

function displayName(member) {
  const name = member.short || member.label || "?";
  const base = name.length > 26 ? name.slice(0, 25) + "…" : name;
  const count = member.count || 1;
  return count > 1 ? `${base} ×${count.toLocaleString("en-US")}` : base;
}

export function computeSccLayout(membersIn) {
  const n = membersIn.length;
  if (!n) return { members: [], edges: [], width: 0, height: 0, headerHeight: HEADER_H };
  const members = membersIn.map((m, id) => {
    const short = displayName(m);
    return {
      id, label: m.label, short, cat: m.cat, count: m.count || 1,
      w: Math.max(MEMBER_MIN_W, Math.min(MEMBER_MAX_W, 12 + short.length * 6.4)),
      h: MEMBER_H, x: 0, y: 0,
    };
  });
  const edges = [];
  const out = members.map(() => []), parents = members.map(() => []);
  for (let s = 0; s < n; s++) {
    for (const [t, mult] of membersIn[s].internal || []) {
      const ei = edges.length;
      edges.push({ s, t, mult, back: false, self: s === t });
      out[s].push(ei);
      parents[t].push(ei);
    }
  }

  // DFS back-edge classification. This is iterative because SCC member graphs
  // can be deep even when the outer shape DAG is shallow.
  const state = new Uint8Array(n);
  for (let start = 0; start < n; start++) {
    if (state[start]) continue;
    state[start] = 1;
    const stack = [[start, 0]];
    while (stack.length) {
      const frame = stack[stack.length - 1], u = frame[0];
      if (frame[1] < out[u].length) {
        const ei = out[u][frame[1]++], v = edges[ei].t;
        if (v === u || state[v] === 1) edges[ei].back = true;
        else if (state[v] === 0) {
          state[v] = 1;
          stack.push([v, 0]);
        }
      } else {
        state[u] = 2;
        stack.pop();
      }
    }
  }

  // Longest-path layers after removing only the cycle-closing edges.
  const indeg = new Int32Array(n), layer = new Int32Array(n);
  for (let i = 0; i < edges.length; i++) if (!edges[i].back) indeg[edges[i].t]++;
  const queue = [];
  for (let u = 0; u < n; u++) if (!indeg[u]) queue.push(u);
  for (let head = 0; head < queue.length; head++) {
    const u = queue[head];
    for (const ei of out[u]) {
      if (edges[ei].back) continue;
      const v = edges[ei].t;
      if (layer[v] < layer[u] + 1) layer[v] = layer[u] + 1;
      if (--indeg[v] === 0) queue.push(v);
    }
  }

  const byLayer = [];
  for (let u = 0; u < n; u++) {
    const l = layer[u];
    (byLayer[l] = byLayer[l] || []).push(u);
  }
  const pos = new Int32Array(n);
  for (const l of byLayer) if (l) for (let i = 0; i < l.length; i++) pos[l[i]] = i;
  const sweep = down => {
    const levels = [...byLayer.keys()];
    if (!down) levels.reverse();
    for (const li of levels) {
      const l = byLayer[li];
      if (!l) continue;
      const keyed = l.map(u => {
        const rel = down ? parents[u] : out[u];
        let sum = 0, count = 0;
        for (const ei of rel) {
          if (edges[ei].back) continue;
          sum += pos[down ? edges[ei].s : edges[ei].t];
          count++;
        }
        return [u, count ? sum / count : null];
      });
      keyed.sort((a, b) => (a[1] === null) - (b[1] === null) ||
        (a[1] ?? 0) - (b[1] ?? 0) || a[0] - b[0]);
      byLayer[li] = keyed.map(k => k[0]);
      for (let i = 0; i < keyed.length; i++) pos[keyed[i][0]] = i;
    }
  };
  sweep(true); sweep(false); sweep(true);

  // Wrap wide layers rather than making broad SCCs into unreadable strips.
  let area = 0;
  for (const m of members) area += (m.w + X_GAP) * (m.h + ROW_GAP);
  const targetW = Math.max(420, Math.sqrt(area));
  const layerRows = byLayer.map(l => {
    if (!l || !l.length) return [];
    const rows = [];
    let ns = [], w = 0;
    for (const u of l) {
      const uw = members[u].w + X_GAP;
      if (ns.length && w + uw > targetW) {
        rows.push({ ns, w: w - X_GAP });
        ns = []; w = 0;
      }
      ns.push(u); w += uw;
    }
    if (ns.length) rows.push({ ns, w: w - X_GAP });
    return rows;
  });
  let contentW = 0, contentH = 0, nonEmptyLayers = 0;
  for (const rows of layerRows) {
    if (!rows.length) continue;
    nonEmptyLayers++;
    contentH += rows.length * MEMBER_H + (rows.length - 1) * ROW_GAP;
    for (const row of rows) if (row.w > contentW) contentW = row.w;
  }
  contentH += Math.max(0, nonEmptyLayers - 1) * LAYER_GAP;
  const width = Math.max(140, contentW + 2 * PADDING);
  const height = HEADER_H + contentH + 2 * PADDING;
  const bodyTop = -height / 2 + HEADER_H + PADDING;
  let y = bodyTop;
  for (const rows of layerRows) {
    if (!rows.length) continue;
    for (const row of rows) {
      let x = -row.w / 2;
      for (const u of row.ns) {
        const m = members[u];
        m.x = x + m.w / 2; m.y = y + m.h / 2;
        x += m.w + X_GAP;
      }
      y += MEMBER_H + ROW_GAP;
    }
    y += LAYER_GAP - ROW_GAP;
  }

  // Freeze cubic geometry and label anchors. Back-edges use a separate right-
  // side lane, so A→B and B→A remain two visibly distinct directed paths.
  for (let i = 0; i < edges.length; i++) {
    const e = edges[i], a = members[e.s], b = members[e.t];
    if (e.self) {
      e.x1 = a.x + a.w / 2; e.y1 = a.y - 4;
      e.x2 = e.x1; e.y2 = a.y + 4;
      e.c1x = e.x1 + 16; e.c1y = a.y - 20;
      e.c2x = e.x2 + 16; e.c2y = a.y + 20;
    } else if (e.back) {
      const lane = Math.max(a.x + a.w / 2, b.x + b.w / 2) + 10 + (i % 3) * 3;
      e.x1 = a.x + a.w / 2; e.y1 = a.y;
      e.x2 = b.x + b.w / 2; e.y2 = b.y;
      e.c1x = lane; e.c1y = e.y1;
      e.c2x = lane; e.c2y = e.y2;
    } else {
      e.x1 = a.x; e.y1 = a.y + a.h / 2;
      e.x2 = b.x; e.y2 = b.y - b.h / 2;
      const my = (e.y1 + e.y2) / 2;
      e.c1x = e.x1; e.c1y = my;
      e.c2x = e.x2; e.c2y = my;
    }
    e.mx = (e.x1 + 3 * e.c1x + 3 * e.c2x + e.x2) / 8;
    e.my = (e.y1 + 3 * e.c1y + 3 * e.c2y + e.y2) / 8;
  }
  return { members, edges, width, height, headerHeight: HEADER_H };
}
