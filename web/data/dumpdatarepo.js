/* data/dumpdatarepo.js — per-dump queries against the HTTP API; owns ALL caching.
 * Cache: Map keyed by dump id, sub-keyed by query — switching dumps can never
 * serve stale data because the key IS the id. Only successful reads are cached
 * (errors, incl. the not-analyzed 404, are re-fetched); Job results are never
 * cached. invalidate(id) drops one dump, invalidate() drops all.
 */
import {api, apiPost} from "./http.js";

const CC = new Map();   // dump id -> Map(queryKey -> Result)

const cached = async (id, key, load) => {
  let sub = CC.get(id);
  if (!sub) CC.set(id, sub = new Map());
  if (sub.has(key)) return sub.get(key);
  const r = await load();
  if (r.ok) sub.set(key, r);
  return r;
};

const dq = id => `/api/dumps/${encodeURIComponent(id)}`;

export const trees = id => cached(id, "trees", () => api(`${dq(id)}/trees`));

export function classes(id, {filter = "", sort = "-s", page = 0} = {}) {
  return cached(id, `classes|${filter}|${sort}|${page}`, () =>
    api(`${dq(id)}/classes?filter=${encodeURIComponent(filter)}&sort=${encodeURIComponent(sort)}&page=${page}`));
}

/* 404 {analyzed:false} passes through as {ok:false, status:404, data:{analyzed:false}} */
export const composition = (id, className) =>
  cached(id, `comp|${className}`, () =>
    api(`${dq(id)}/composition?class=${encodeURIComponent(className)}`));

export function anatomy(id, className, {samples = null} = {}) {
  const path = `${dq(id)}/anatomy?class=${encodeURIComponent(className)}` +
    (samples ? `&samples=${samples}` : "");
  return cached(id, `anat|${className}|${samples ?? ""}`, () => api(path));
}

export const compare = (aId, bId) =>
  cached(aId, `compare|${bId}`, () =>
    api(`/api/compare?a=${encodeURIComponent(aId)}&b=${encodeURIComponent(bId)}`));

/* starts a server-side MAT analysis; the returned Job is NOT cached */
export const analyze = (id, className, {samples = 8, anatomy = true} = {}) =>
  apiPost(`${dq(id)}/analyze`, {"class": className, samples, anatomy});

export function invalidate(id = null) {
  if (id == null) CC.clear();
  else CC.delete(id);
}
