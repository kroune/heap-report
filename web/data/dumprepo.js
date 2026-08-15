/* data/dumprepo.js — dump list + lifecycle ops + job polling.
 * DumpInfo = {id, state:'remote'|'downloading'|'assembling'|'indexing'|'ready'|'failed',
 *             source, size, error, progress:{done,total}|null, meta}
 * Job = {id, kind, dump, detail, state:'queued'|'running'|'done'|'failed',
 *        progress:{done,total}|null, log:[str], error}
 */
import {api, apiPost, apiDel} from "./http.js";

export const listDumps = () => api("/api/dumps");

export const startDownload = id =>
  apiPost(`/api/dumps/${encodeURIComponent(id)}/download`);

export const retryDownload = id =>
  apiPost(`/api/dumps/${encodeURIComponent(id)}/retry`);

export const deleteDump = id => apiDel(`/api/dumps/${encodeURIComponent(id)}`);

/* Polls GET /api/jobs every ms, invokes onJobs([Job]), returns a stop function.
 * A failed poll is logged and skipped — polling continues, onJobs is only
 * called with a real jobs array. */
export function pollJobs(onJobs, ms = 2500) {
  let stopped = false, timer = 0;
  const tick = async () => {
    const r = await api("/api/jobs");
    if (stopped) return;
    if (r.ok && Array.isArray(r.data)) onJobs(r.data);
    else console.warn("pollJobs: poll failed:", r.error || r.status);
    timer = setTimeout(tick, ms);
  };
  tick();
  return () => { stopped = true; clearTimeout(timer); };
}
