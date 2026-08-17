/* data/inlinerepo.js — same interface and Result shapes as dumpdatarepo.js,
 * reading an inlined snapshot payload instead of the HTTP API.
 *
 * PAYLOAD CONTRACT (what backend/snapshot.py must inline as window.__INLINE__):
 * {
 *   name:    string                                  dump name
 *   stats:   {totalRetained, totalShallow, totalObjects, classes, analyzed,
 *             modules, buildFileBytes, dump}         -> trees().data.stats
 *   trees:   {dom, hist}                             treemap trees
 *             (package nodes with children, leaves {name,disp,pkg,cat,c,s,r?,leaf})
 *                                                    -> trees().data.trees
 *   classes: [[disp, c, s, r, comp, anat, lams], ...]  full class table:
 *             r = retained bytes|null, comp = 0/1, anat = [sampleCounts],
 *             lams = [[name,c,s],...]|null           -> mapped to class rows below
 *   comps:   {className: compositionPayload}         only analyzed classes
 *                                                    -> composition().data
 *   anats:   {className: anatomyPayload}             only analyzed classes,
 *             default sample count                   -> anatomy().data
 * }
 *
 * Class rows are mapped to the server class-table shape
 * {disp,name,pkg,cat,c,s,pi,r,comp,anat,lams,analyzable} and filtered/sorted/
 * paged client-side (page size 200, like the server).
 * Ops that need the server (analyze) and compare (a snapshot holds one dump)
 * return {ok:false, code:'snapshot', error:'static snapshot'}.
 */

import {catOf} from "./http.js";

const SNAP = () => ({ok:false, code:"snapshot", error:"static snapshot"});

const NOT_ANALYZED = {ok:false, status:404, data:{analyzed:false}};

export function makeInlineRepo(payload) {
  const ICLS = payload.classes.map(a => ({
    disp: a[0], c: a[1], s: a[2],
    pi: a[1] ? Math.round(a[2] / a[1] * 10) / 10 : 0,
    r: a[3], comp: !!a[4], anat: a[5] || [], lams: a[6] || null,
    analyzable: !a[0].endsWith("$$Lambda*"),
    name: a[0].split(".").pop(),
    pkg: a[0].includes(".") ? a[0].slice(0, a[0].lastIndexOf(".")) : "(no package)",
    cat: catOf(a[0]),
  }));

  const trees = async () => ({ok:true, data: {stats: payload.stats, trees: payload.trees}});

  const classes = async (id, {filter = "", sort = "-s", page = 0} = {}) => {
    const f = filter.trim().toLowerCase();
    let rows = f ? ICLS.filter(r => r.disp.toLowerCase().includes(f)) : ICLS;
    const k = sort.replace("-", "");
    const key = r => k === "name" ? r.disp.toLowerCase() : (k === "r" ? -(r.r ?? -1) : -r[k]);
    rows = [...rows].sort((a, b) => { const x = key(a), y = key(b); return x < y ? -1 : x > y ? 1 : 0; });
    if (k === "r") rows.sort((a, b) => (a.r == null) - (b.r == null) || (b.r - a.r));
    const total = rows.length, pages = Math.max(1, Math.ceil(total / 200));
    return {ok:true, data: {rows: rows.slice(page * 200, page * 200 + 200), total, page, pages}};
  };

  const composition = async (id, className) => {
    const c = payload.comps[className];
    return c ? {ok:true, data: c} : NOT_ANALYZED;
  };

  /* anatomy at the default sample count is inlined */
  const anatomy = async (id, className, {samples = null} = {}) => {
    const a = samples == null ? payload.anats[className] : null;
    return a ? {ok:true, data: a} : NOT_ANALYZED;
  };

  const compare = async () => SNAP();
  const analyze = async () => SNAP();
  const invalidate = () => {};   // nothing is fetched, so nothing is cached

  return {trees, classes, composition, anatomy, compare, analyze, invalidate};
}
