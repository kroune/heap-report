/* data/http.js — the ONLY module allowed to call fetch.
 * Every call resolves to a Result; nothing in the UI try/catches the data layer:
 *   ok:           {ok:true, data}          (204 -> data:null)
 *   HTTP error:   {ok:false, status, ...body, data:body}  — an {"error","code"}
 *                 body is spread in; data carries the parsed body (e.g. the
 *                 404 {analyzed:false} of composition/anatomy)
 *   network fail: {ok:false, status:0, code:'network', error}
 * Shared helpers: esc (escapes & < > " '), fmtB (bytes), fmtN (number
 * grouping), fmtDur (seconds -> compact duration), fmtSrc (download lane
 * badge label).
 */

export const esc = s => String(s)
  .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
  .replace(/"/g, "&quot;").replace(/'/g, "&#39;");

/* download source lane ("s3"|"github", from progress.source) -> badge label */
export const fmtSrc = s => s === "s3" ? "S3" : s === "github" ? "GitHub" : String(s || "");

/* bytes -> compact: 1.5G / 320M / 12K / 999B */
export const fmtB = v => {
  const u = v >= 1e9 ? [1e9, "G"] : v >= 1e6 ? [1e6, "M"] : v >= 1e3 ? [1e3, "K"] : [1, "B"];
  const n = v / u[0];
  return (n >= 100 ? Math.round(n) : Math.round(n * 10) / 10) + u[1];
};

/* number grouping: 1234567 -> "1,234,567" */
export const fmtN = v => Number(v).toLocaleString("en-US");

/* seconds -> compact duration: 42s / 6m 30s / 1h 5m */
export const fmtDur = v => {
  const s = Math.max(0, Math.round(v));
  if (s < 60) return s + "s";
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ${s % 60}s`;
  return `${Math.floor(m / 60)}h ${m % 60}m`;
};

/* class -> category; mirrors backend cat_of (mat.py) exactly. */
export const catOf = n =>
  n.startsWith("org.gradle") ? "gradle"
  : n.startsWith("com.android") ? "agp"
  : n.startsWith("org.jetbrains.kotlin") ? "kotlin"
  : (n.startsWith("java.") || n.startsWith("jdk.") || n.startsWith("sun.")
     || n.startsWith("com.sun") || !n.includes(".")) ? "jdk" : "other";

const req = async (path, opts) => {
  let r;
  try { r = await fetch(path, opts); } catch (e) {
    return {ok:false, status:0, code:"network", error:String(e && e.message || e)};
  }
  if (r.status === 204) return {ok:true, data:null};
  let body = null;
  try { body = await r.json(); } catch (e) { /* non-JSON or empty body */ }
  if (r.ok) return {ok:true, data:body};
  const err = {ok:false, status:r.status, code:"http", error:"HTTP " + r.status};
  if (body && typeof body === "object") { Object.assign(err, body); err.data = body; }
  return err;
};

export const api = path => req(path);

export const apiPost = (path, body) => req(path, {
  method: "POST",
  headers: {"Content-Type": "application/json"},
  body: JSON.stringify(body ?? {}),
});

export const apiDel = path => req(path, {method: "DELETE"});
