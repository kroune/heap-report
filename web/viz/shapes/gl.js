/* Shapes viz — WebGL2 bulk renderer. Everything is visible at every zoom and
   no frame ever waits for a debounced bitmap:

   - Wide views draw ALL edges as GL_LINES (each is sub-pixel wide there, so
     a 1px anti-blended line with coverage alpha is exact) and all plain
     nodes as de-instanced indexed quads with texture vertex pulling —
     no per-instance GPU setup cost on the 382k-edge whole-heap payload.
   - Zoomed views draw only the viewport-culled edge set (canvas.js supplies
     ids from its spatial index) as instanced cubic-bezier strips with
     analytic anti-aliasing and real pixel widths.
   - Pass-through edges (neither endpoint on screen) are dimmed so local
     structure reads above transit traffic.
   - Selection re-renders the picked node's edges on top in full quality.

   SCC containers, labels and selection decorations stay on the Canvas-2D
   overlay (canvas.js). No DOM beyond the supplied canvas, no fetch, no
   timers. createShapesGl returns null when WebGL2 is unavailable. */

const EDGE_SEGMENTS = 12;                       // bezier strip tessellation (culled pass)
const EDGE_MIN_PX = 0.75;                       // sub-pixel edges clamp to this
const EDGE_ALPHA_FLOOR = 0.015;                 // coverage alpha floor (keeps structure at fit)
const EDGE_AA_PX = 0.75;                        // analytic edge AA (MSAA is too costly at 4Mpx)
const TRANSIT_DIM = 0.35;                       // pass-through edge dim factor
const EDGE_RGBA = [74 / 255, 85 / 255, 104 / 255, 0.5];      // C_EDGE
const EDGE_OUT_RGBA = [121 / 255, 184 / 255, 255 / 255, 0.95]; // C_EDGE_OUT
const EDGE_IN_RGBA = [240 / 255, 136 / 255, 62 / 255, 0.95];   // C_EDGE_IN
const NODE_STROKE = [13 / 255, 17 / 255, 23 / 255];           // C_NODE_STROKE
const ROOT_STROKE = [232 / 255, 237 / 255, 245 / 255];        // C_ROOT_STROKE

const VIEW_UNIFORMS = `
uniform vec2 u_translate;
uniform float u_scale;
uniform vec2 u_view;`;

const CLIP = `
vec4 clipOf(vec2 screen) {
  return vec4(screen.x / u_view.x * 2.0 - 1.0, 1.0 - screen.y / u_view.y * 2.0, 0.0, 1.0);
}`;

/* Culled zoom pass: instanced cubic bezier ribbons, matching the Canvas-2D
   curve geometry (control points on the vertical midline). */
const EDGE_QUAD_VERT = `#version 300 es
precision highp float;
layout(location=0) in vec2 a_param;   // x: t along the curve, y: side (+-1)
layout(location=1) in vec4 a_ep;      // x0,y0 (source anchor) x1,y1 (target anchor)
layout(location=2) in float a_w;      // world-space width
${VIEW_UNIFORMS}
uniform float u_minPx;
uniform float u_widthBoost;
uniform float u_transitDim;
out float v_cov;
out float v_d;        // signed px distance from the centerline
flat out float v_half; // half draw width in px
flat out float v_transit;
${CLIP}
void main() {
  vec2 p0 = a_ep.xy, p3 = a_ep.zw;
  float my = (p0.y + p3.y) * 0.5;
  vec2 p1 = vec2(p0.x, my), p2 = vec2(p3.x, my);
  float t = a_param.x, mt = 1.0 - t;
  vec2 pos = mt*mt*mt*p0 + 3.0*mt*mt*t*p1 + 3.0*mt*t*t*p2 + t*t*t*p3;
  vec2 tangent = 3.0*mt*mt*(p1-p0) + 6.0*mt*t*(p2-p1) + 3.0*t*t*(p3-p2);
  if (dot(tangent, tangent) < 1e-12) tangent = p3 - p0;
  if (dot(tangent, tangent) < 1e-12) tangent = vec2(0.0, 1.0);
  vec2 n = normalize(vec2(-tangent.y, tangent.x));
  float wpx = a_w * u_scale * u_widthBoost;
  float drawPx = max(wpx, u_minPx);
  v_cov = clamp(wpx / drawPx, 0.0, 1.0);
  float halfPx = drawPx * 0.5 + ${EDGE_AA_PX.toFixed(4)};
  v_half = halfPx;
  v_d = a_param.y * halfPx;
  pos += n * (halfPx / u_scale) * a_param.y;
  vec2 s0 = p0 * u_scale + u_translate;
  vec2 s3 = p3 * u_scale + u_translate;
  bool in0 = all(greaterThanEqual(s0, vec2(0.0))) && all(lessThanEqual(s0, u_view));
  bool in3 = all(greaterThanEqual(s3, vec2(0.0))) && all(lessThanEqual(s3, u_view));
  v_transit = (in0 || in3) ? 1.0 : u_transitDim;
  gl_Position = clipOf(pos * u_scale + u_translate);
}`;

const EDGE_QUAD_FRAG = `#version 300 es
precision mediump float;
uniform vec4 u_color;
uniform float u_alphaFloor;
in float v_cov;
in float v_d;
flat in float v_half;
flat in float v_transit;
out vec4 o_color;
void main() {
  float aa = clamp((v_half - abs(v_d)) / ${EDGE_AA_PX.toFixed(4)}, 0.0, 1.0);
  float a = u_color.a * max(v_cov, u_alphaFloor) * aa * v_transit;
  o_color = vec4(u_color.rgb * a, a);
}`;

/* Wide pass: plain 1px lines. Edges that would exceed line width are only
   possible when few edges are visible — and then the quad pass runs instead. */
const LINE_VERT = `#version 300 es
precision highp float;
layout(location=0) in vec2 a_pos;     // this endpoint (anchor, world)
layout(location=1) in vec2 a_other;   // the opposite endpoint (world)
layout(location=2) in float a_w;      // world-space width
${VIEW_UNIFORMS}
uniform float u_transitDim;
flat out float v_alpha;
${CLIP}
void main() {
  float wpx = a_w * u_scale;
  float cov = clamp(wpx, 0.0, 1.0);
  vec2 s0 = a_pos * u_scale + u_translate;
  vec2 s1 = a_other * u_scale + u_translate;
  bool in0 = all(greaterThanEqual(s0, vec2(0.0))) && all(lessThanEqual(s0, u_view));
  bool in1 = all(greaterThanEqual(s1, vec2(0.0))) && all(lessThanEqual(s1, u_view));
  v_alpha = max(cov, ${EDGE_ALPHA_FLOOR.toFixed(4)}) * ((in0 || in1) ? 1.0 : u_transitDim);
  gl_Position = clipOf(s0);
}`;

const LINE_FRAG = `#version 300 es
precision mediump float;
uniform vec4 u_color;
flat in float v_alpha;
out vec4 o_color;
void main() {
  float a = u_color.a * v_alpha;
  o_color = vec4(u_color.rgb * a, a);
}`;

/* Nodes: de-instanced quads, two triangles per node, data pulled from
   textures by vertex id. Circles with SDF anti-aliasing and border rings. */
const NODE_VERT = `#version 300 es
precision highp float;
${VIEW_UNIFORMS}
uniform sampler2D u_geom;    // x, y, rad, root
uniform sampler2D u_fill;    // category rgb
out vec2 v_local;      // px offset from center
out float v_radPx;
out vec3 v_rgb;
out float v_root;
${CLIP}
void main() {
  int id = gl_VertexID >> 2;
  int corner = gl_VertexID & 3;
  ivec2 at = ivec2(id & 1023, id >> 10);
  vec4 geom = texelFetch(u_geom, at, 0);
  vec2 c = vec2(corner == 1 || corner == 3 ? 1.0 : -1.0, corner >= 2 ? 1.0 : -1.0);
  float radPx = max(geom.z * u_scale, 0.5);
  float pad = radPx + 2.0;
  vec2 center = geom.xy * u_scale + u_translate;
  v_local = c * pad;
  v_radPx = radPx;
  v_rgb = texelFetch(u_fill, at, 0).rgb;
  v_root = geom.w;
  gl_Position = clipOf(center + c * pad);
}`;

const NODE_FRAG = `#version 300 es
precision mediump float;
uniform vec3 u_stroke;
uniform vec3 u_rootStroke;
in vec2 v_local;
in float v_radPx;
in vec3 v_rgb;
in float v_root;
out vec4 o_color;
void main() {
  float d = length(v_local) - v_radPx;
  float aa = 0.75;
  float fill = 1.0 - smoothstep(-aa, aa, d);
  if (fill <= 0.0) discard;
  bool root = v_root > 0.5;
  float bw = v_radPx >= 6.0 ? (root ? 1.6 : 0.8) : 0.0;
  float ring = bw > 0.0 ? (1.0 - smoothstep(-aa, aa, -(d + bw))) : 0.0;
  vec3 rgb = mix(v_rgb, root ? u_rootStroke : u_stroke, ring);
  float a = fill * mix(0.85, 1.0, ring);
  o_color = vec4(rgb * a, a);
}`;

function compile(gl, type, src) {
  const sh = gl.createShader(type);
  gl.shaderSource(sh, src);
  gl.compileShader(sh);
  if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS))
    throw new Error("shader: " + gl.getShaderInfoLog(sh));
  return sh;
}

function link(gl, vs, fs) {
  const p = gl.createProgram();
  gl.attachShader(p, compile(gl, gl.VERTEX_SHADER, vs));
  gl.attachShader(p, compile(gl, gl.FRAGMENT_SHADER, fs));
  gl.linkProgram(p);
  if (!gl.getProgramParameter(p, gl.LINK_STATUS))
    throw new Error("program: " + gl.getProgramInfoLog(p));
  return p;
}

function parseHex(color) {
  const m = /^#([0-9a-f]{6})$/i.exec(color || "");
  if (!m) return [125, 133, 144];
  const v = parseInt(m[1], 16);
  return [(v >> 16) & 255, (v >> 8) & 255, v & 255];
}

/* Strip geometry shared by every culled edge instance: (t, side) pairs. */
function edgeStrip() {
  const verts = new Float32Array((EDGE_SEGMENTS + 1) * 4);
  for (let i = 0; i <= EDGE_SEGMENTS; i++) {
    const t = i / EDGE_SEGMENTS;
    verts[i * 4] = t; verts[i * 4 + 1] = 1;
    verts[i * 4 + 2] = t; verts[i * 4 + 3] = -1;
  }
  return verts;
}

export function createShapesGl(canvas) {
  let gl = null;
  try {
    gl = canvas.getContext("webgl2", {
      alpha: true, antialias: false, premultipliedAlpha: true,
      depth: false, stencil: false, powerPreference: "high-performance",
    });
  } catch { gl = null; }
  if (!gl) return null;

  const quadProg = link(gl, EDGE_QUAD_VERT, EDGE_QUAD_FRAG);
  const lineProg = link(gl, LINE_VERT, LINE_FRAG);
  const nodeProg = link(gl, NODE_VERT, NODE_FRAG);
  const U = (p, names) => Object.fromEntries(names.map(n => [n, gl.getUniformLocation(p, "u_" + n)]));
  const quadU = U(quadProg, ["translate", "scale", "view", "minPx", "widthBoost", "color",
    "alphaFloor", "transitDim"]);
  const lineU = U(lineProg, ["translate", "scale", "view", "color", "transitDim"]);
  const nodeU = U(nodeProg, ["translate", "scale", "view", "stroke", "rootStroke", "geom", "fill"]);

  const stripBuf = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, stripBuf);
  gl.bufferData(gl.ARRAY_BUFFER, edgeStrip(), gl.STATIC_DRAW);
  const cullBuf = gl.createBuffer(), selOutBuf = gl.createBuffer(), selInBuf = gl.createBuffer();
  const lineBuf = gl.createBuffer();
  const nodeIdx = gl.createBuffer();
  const geomTex = gl.createTexture(), fillTex = gl.createTexture();

  function quadVao(instBuf) {
    const vao = gl.createVertexArray();
    gl.bindVertexArray(vao);
    gl.bindBuffer(gl.ARRAY_BUFFER, stripBuf);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 8, 0);
    gl.bindBuffer(gl.ARRAY_BUFFER, instBuf);
    gl.enableVertexAttribArray(1);
    gl.vertexAttribPointer(1, 4, gl.FLOAT, false, 20, 0);
    gl.vertexAttribDivisor(1, 1);
    gl.enableVertexAttribArray(2);
    gl.vertexAttribPointer(2, 1, gl.FLOAT, false, 20, 16);
    gl.vertexAttribDivisor(2, 1);
    gl.bindVertexArray(null);
    return vao;
  }
  const cullVao = quadVao(cullBuf);
  const selOutVao = quadVao(selOutBuf);
  const selInVao = quadVao(selInBuf);

  const lineVao = gl.createVertexArray();
  gl.bindVertexArray(lineVao);
  gl.bindBuffer(gl.ARRAY_BUFFER, lineBuf);
  gl.enableVertexAttribArray(0);
  gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 20, 0);
  gl.enableVertexAttribArray(1);
  gl.vertexAttribPointer(1, 2, gl.FLOAT, false, 20, 8);
  gl.enableVertexAttribArray(2);
  gl.vertexAttribPointer(2, 1, gl.FLOAT, false, 20, 16);
  gl.bindVertexArray(null);

  const nodeVao = gl.createVertexArray();
  gl.bindVertexArray(nodeVao);
  gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, nodeIdx);
  gl.bindVertexArray(null);

  gl.disable(gl.DEPTH_TEST);
  gl.enable(gl.BLEND);
  gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);   // premultiplied alpha

  let edgeData = null;    // Float32Array, 5 floats per edge — selection/cull slices
  let edgeCount = 0, nodeCount = 0;
  let cullCount = -1;     // -1: no cull list -> wide line pass
  let selOutCount = 0, selInCount = 0;
  let disposed = false;

  function upload(N, E, ew, fills) {
    edgeCount = E.length;
    edgeData = new Float32Array(edgeCount * 5);
    const lines = new Float32Array(edgeCount * 10);
    for (let i = 0; i < edgeCount; i++) {
      const a = N[E[i].s], t = N[E[i].t], o = i * 5;
      const x0 = a.x, y0 = a.y + a.hh + 2, x1 = t.x, y1 = t.y - t.hh - 4;
      edgeData[o] = x0; edgeData[o + 1] = y0;
      edgeData[o + 2] = x1; edgeData[o + 3] = y1;
      edgeData[o + 4] = ew[i];
      const l = i * 10;
      lines[l] = x0; lines[l + 1] = y0; lines[l + 2] = x1; lines[l + 3] = y1; lines[l + 4] = ew[i];
      lines[l + 5] = x1; lines[l + 6] = y1; lines[l + 7] = x0; lines[l + 8] = y0; lines[l + 9] = ew[i];
    }
    gl.bindBuffer(gl.ARRAY_BUFFER, lineBuf);
    gl.bufferData(gl.ARRAY_BUFFER, lines, gl.STATIC_DRAW);

    // SCC containers are painted by the overlay; plain nodes go to textures.
    let plain = 0;
    for (let u = 0; u < N.length; u++) if (!N[u].scc) plain++;
    nodeCount = plain;
    const rows = Math.max(1, Math.ceil(plain / 1024));
    const geom = new Float32Array(1024 * rows * 4);
    const fill = new Uint8Array(1024 * rows * 4);
    let w = 0;
    for (let u = 0; u < N.length; u++) {
      const n = N[u];
      if (n.scc) continue;
      geom[w * 4] = n.x; geom[w * 4 + 1] = n.y; geom[w * 4 + 2] = n.rad;
      geom[w * 4 + 3] = n.roots ? 1 : 0;
      const rgb = parseHex(fills[u]);
      fill[w * 4] = rgb[0]; fill[w * 4 + 1] = rgb[1]; fill[w * 4 + 2] = rgb[2]; fill[w * 4 + 3] = 255;
      w++;
    }
    const tex = (t, unit, internal, format, type, data) => {
      gl.activeTexture(gl.TEXTURE0 + unit);
      gl.bindTexture(gl.TEXTURE_2D, t);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
      gl.texImage2D(gl.TEXTURE_2D, 0, internal, 1024, rows, 0, format, type, data);
    };
    tex(geomTex, 0, gl.RGBA32F, gl.RGBA, gl.FLOAT, geom);
    tex(fillTex, 1, gl.RGBA8, gl.RGBA, gl.UNSIGNED_BYTE, fill);

    const idx = new Uint32Array(plain * 6);
    for (let i = 0; i < plain; i++) {
      const v = i * 4, o = i * 6;
      idx[o] = v; idx[o + 1] = v + 1; idx[o + 2] = v + 2;
      idx[o + 3] = v + 2; idx[o + 4] = v + 1; idx[o + 5] = v + 3;
    }
    gl.bindVertexArray(nodeVao);
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, nodeIdx);
    gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, idx, gl.STATIC_DRAW);
    gl.bindVertexArray(null);
  }

  function fillIds(buf, ids) {
    if (!ids || !ids.length || !edgeData) {
      gl.bindBuffer(gl.ARRAY_BUFFER, buf);
      gl.bufferData(gl.ARRAY_BUFFER, 0, gl.DYNAMIC_DRAW);
      return 0;
    }
    const data = new Float32Array(ids.length * 5);
    for (let i = 0; i < ids.length; i++)
      data.set(edgeData.subarray(ids[i] * 5, ids[i] * 5 + 5), i * 5);
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, data, gl.DYNAMIC_DRAW);
    return ids.length;
  }

  /* ids: viewport-culled edge list for the zoom pass, or null for the wide
     line pass. canvas.js recomputes it only when the viewport changes. */
  function setCull(ids) {
    cullCount = ids ? fillIds(cullBuf, ids) : -1;
  }

  /* outIds/inIds: edge indices (or null to clear). */
  function setSelection(outIds, inIds) {
    selOutCount = fillIds(selOutBuf, outIds);
    selInCount = fillIds(selInBuf, inIds);
  }

  function drawQuadPass(vao, count, zm, w, h, color, widthBoost, minPx, alphaFloor, transitDim) {
    if (!count) return;
    gl.bindVertexArray(vao);
    gl.uniform2f(quadU.translate, zm.x, zm.y);
    gl.uniform1f(quadU.scale, zm.k);
    gl.uniform2f(quadU.view, w, h);
    gl.uniform1f(quadU.minPx, minPx);
    gl.uniform1f(quadU.widthBoost, widthBoost);
    gl.uniform1f(quadU.alphaFloor, alphaFloor);
    gl.uniform1f(quadU.transitDim, transitDim);
    gl.uniform4f(quadU.color, color[0], color[1], color[2], color[3]);
    gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, (EDGE_SEGMENTS + 1) * 2, count);
  }

  function draw(zm, w, h, dpr) {
    if (disposed || gl.isContextLost()) return;
    const pw = Math.max(1, Math.round(w * dpr)), ph = Math.max(1, Math.round(h * dpr));
    if (canvas.width !== pw || canvas.height !== ph) { canvas.width = pw; canvas.height = ph; }
    gl.viewport(0, 0, pw, ph);
    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT);

    if (cullCount >= 0) {
      gl.useProgram(quadProg);
      drawQuadPass(cullVao, cullCount, zm, w, h, EDGE_RGBA, 1, EDGE_MIN_PX,
        EDGE_ALPHA_FLOOR, TRANSIT_DIM);
    } else if (edgeCount) {
      gl.useProgram(lineProg);
      gl.bindVertexArray(lineVao);
      gl.uniform2f(lineU.translate, zm.x, zm.y);
      gl.uniform1f(lineU.scale, zm.k);
      gl.uniform2f(lineU.view, w, h);
      gl.uniform1f(lineU.transitDim, TRANSIT_DIM);
      gl.uniform4f(lineU.color, EDGE_RGBA[0], EDGE_RGBA[1], EDGE_RGBA[2], EDGE_RGBA[3]);
      gl.drawArrays(gl.LINES, 0, edgeCount * 2);
    }

    if (nodeCount) {
      gl.useProgram(nodeProg);
      gl.bindVertexArray(nodeVao);
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, geomTex);
      gl.activeTexture(gl.TEXTURE1);
      gl.bindTexture(gl.TEXTURE_2D, fillTex);
      gl.uniform1i(nodeU.geom, 0);
      gl.uniform1i(nodeU.fill, 1);
      gl.uniform2f(nodeU.translate, zm.x, zm.y);
      gl.uniform1f(nodeU.scale, zm.k);
      gl.uniform2f(nodeU.view, w, h);
      gl.uniform3f(nodeU.stroke, NODE_STROKE[0], NODE_STROKE[1], NODE_STROKE[2]);
      gl.uniform3f(nodeU.rootStroke, ROOT_STROKE[0], ROOT_STROKE[1], ROOT_STROKE[2]);
      gl.drawElements(gl.TRIANGLES, nodeCount * 6, gl.UNSIGNED_INT, 0);
    }

    if (selOutCount || selInCount) {
      gl.useProgram(quadProg);
      drawQuadPass(selOutVao, selOutCount, zm, w, h, EDGE_OUT_RGBA, 1.5, 1.25, 1, 1);
      drawQuadPass(selInVao, selInCount, zm, w, h, EDGE_IN_RGBA, 1.5, 1.25, 1, 1);
    }
    gl.bindVertexArray(null);
  }

  function dispose() {
    if (disposed) return;
    disposed = true;
    edgeData = null;
    for (const b of [stripBuf, cullBuf, selOutBuf, selInBuf, lineBuf, nodeIdx]) gl.deleteBuffer(b);
    for (const v of [cullVao, selOutVao, selInVao, lineVao, nodeVao]) gl.deleteVertexArray(v);
    gl.deleteTexture(geomTex);
    gl.deleteTexture(fillTex);
    gl.deleteProgram(quadProg);
    gl.deleteProgram(lineProg);
    gl.deleteProgram(nodeProg);
    const lose = gl.getExtension("WEBGL_lose_context");
    if (lose) lose.loseContext();
    canvas.width = 1;
    canvas.height = 1;
  }

  return { upload, setCull, setSelection, draw, dispose };
}
