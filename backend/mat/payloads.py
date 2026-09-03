"""backend/mat/payloads — parsed structures -> JSON-serializable payloads.

Pure functions only: no I/O, no caching, no process state. The HTTP layer
passes the returned dicts through untouched, so payload keys here are the API
contract of the query endpoints.
"""
from __future__ import annotations

from .parsing import (_merge_fams, _short, _split_refs, cat_of, norm_lambda,
                      split_pkg)

HIST_MIN_SHALLOW = 96 * 1024  # classes smaller than this fold into "· other ·" per package (treemap only)
TREE_NODE_BUDGET = 20000   # fold-overflow nodes kept per anatomy payload (stats stay exact regardless)

PROXIES = [
    "org.gradle.api.internal.initialization.DefaultScriptHandler_Decorated",
    "org.gradle.api.internal.artifacts.configurations.DefaultLegacyConfiguration_Decorated",
    "org.gradle.api.internal.artifacts.configurations.DefaultConfigurationContainer_Decorated",
    "com.android.build.api.artifact.impl.ArtifactsImpl",
    "org.gradle.api.internal.tasks.DefaultTaskContainer$TaskCreatingProvider_Decorated",
    "com.android.build.gradle.internal.lint.AndroidLintAnalysisTask_Decorated",
    "com.android.build.gradle.internal.lint.AndroidLintTask_Decorated",
    "com.android.build.gradle.internal.lint.LintModelWriterTask_Decorated",
    "org.jetbrains.kotlin.gradle.tasks.KotlinCompile_Decorated",
    "org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.builder.ResolveState",
    "org.gradle.api.internal.artifacts.ivyservice.resolveengine.result.ComponentResult",
]


# ---------------------------------------------------------------- overview / classes

def _stats_build(hist, dom, meta, idx):
    return {
        "totalRetained": sum(r[3] for r in dom),
        "totalShallow": sum(r[2] for r in hist),
        "totalObjects": sum(r[1] for r in hist),
        "classes": len(hist),
        "analyzed": sum(1 for st in idx.values() if st["comp"]),
        "modules": int(meta.get("modules", 3010)),
        "buildFileBytes": meta.get("buildFileBytes", 0),
        "dump": meta.get("dump", ""),
    }


def _class_table_build(hist, idx, tots):
    """One row per class (lambda families merged), joined with analysis status.
    Row: {disp,name,pkg,cat,c,s,pi,r,comp,anat,lams,analyzable}"""
    fams = {}
    for name, c, s in hist:
        disp = norm_lambda(name)
        e = fams.get(disp)
        if e is None:
            e = fams[disp] = {"disp": disp, "c": 0, "s": 0, "lams": []}
        e["c"] += c
        e["s"] += s
        if disp != name:
            e["lams"].append([name, c, s])
    rows = []
    for disp, e in fams.items():
        st = idx.get(disp)
        r = tots.get(disp)
        pkg, nm = split_pkg(disp)
        rows.append({
            "disp": disp, "name": nm, "pkg": pkg, "cat": cat_of(disp),
            "c": e["c"], "s": e["s"],
            "pi": round(e["s"] / e["c"], 1) if e["c"] else 0,
            "r": r[0] if r else None,
            "comp": bool(st and st["comp"]),
            "anat": st["anat"] if st else [],
            "lams": e["lams"] or None,
            "analyzable": not disp.endswith("$$Lambda*"),
        })
    return rows


def _build_tree(leaves):
    cats = {}
    for lf in leaves:
        cat = lf["cat"]
        pkg = lf.pop("pkg")
        node = cats.setdefault(cat, {"name": {"gradle": "Gradle core", "agp": "Android (AGP)",
                                              "kotlin": "Kotlin plugin", "jdk": "JDK / collections",
                                              "other": "Other"}[cat], "cat": cat, "pkgs": {}})
        node["pkgs"].setdefault(pkg, []).append(lf)
    out = {"name": "all", "children": []}
    for cid in ["gradle", "agp", "kotlin", "jdk", "other"]:
        if cid not in cats:
            continue
        c = cats[cid]
        pkgs = [{"name": p, "cat": cid, "children": sorted(ls, key=lambda x: -(x.get("r") or x["s"]))}
                for p, ls in c["pkgs"].items()]
        pkgs.sort(key=lambda p: -sum(l.get("r") or l["s"] for l in p["children"]))
        out["children"].append({"name": c["name"], "cat": cid, "children": pkgs})
    return out


def _trees_build(hist, dom):
    """Dominator (top-level only) + histogram trees for the treemap, lambda-merged."""
    dom_leaves = []
    for d, v in _merge_fams(dom, 3).items():
        o, s, r = v
        pkg, name = split_pkg(d)
        dom_leaves.append({"name": name, "disp": d, "pkg": pkg, "cat": cat_of(d),
                           "c": o, "s": s, "r": r, "leaf": 1})
    hist_leaves, other = [], {}
    for d, v in _merge_fams(hist, 2).items():
        o, s = v
        if s >= HIST_MIN_SHALLOW:
            pkg, name = split_pkg(d)
            hist_leaves.append({"name": name, "disp": d, "pkg": pkg, "cat": cat_of(d),
                                "c": o, "s": s, "leaf": 1})
        else:
            k = (cat_of(d), split_pkg(d)[0])
            acc = other.setdefault(k, [0, 0, 0])
            acc[0] += o
            acc[1] += s
            acc[2] += 1
    for (cat, pkg), (cnt, shallow, n) in other.items():
        hist_leaves.append({"name": f"· other ({n} classes)", "disp": f"{pkg} — {n} smaller classes",
                            "pkg": pkg, "cat": cat, "c": cnt, "s": shallow, "leaf": 1})
    return {"dom": _build_tree(dom_leaves), "hist": _build_tree(hist_leaves)}


def _composition_build(rows):
    """rows = [(cls, objects, shallow)] — the retained-set histogram of one
    analyzed class (db.read_rs_rows)."""
    rows = sorted(rows, key=lambda r: -r[2])
    return {"rows": [[c, s, n] for c, n, s in rows[:100]],
            "totalShallow": sum(s for _, _, s in rows),
            "totalObjects": sum(n for _, n, _ in rows),
            "classes": len(rows)}


# ---------------------------------------------------------------- anatomy (full-graph aggregated reference tree)

def _new_agg(label, cls, sk=False):
    """Aggregate tree node. `refs` is the in-set inbound reference count (refs >
    n ⇒ shared within the set); `sk` marks synthetic fields (this$0, …)."""
    n = {"name": label, "full": cls, "n": 0, "s": 0, "r": 0, "kids": {}, "refs": 0}
    if sk:
        n["sk"] = 1
    return n


def _finish_agg(n, max_kids, budget=None):
    """budget: one-element list, fold-overflow nodes the payload may still
    keep (None = fresh default). The overflow of a "· N more" fold is kept
    (biggest first) only while the budget lasts — an unbounded fold made the
    payload grow with the extraction's object count, which is what made giant
    analyses unopenable. The fold's summed n/s/r stay exact either way."""
    if budget is None:
        budget = [TREE_NODE_BUDGET]
    kids = sorted(n["kids"].values(), key=lambda k: (-k["r"], -k["s"]))
    more = None
    if len(kids) > max_kids:
        # pure UI collapse: the overflow keeps its structure inside a "· N more"
        # fold node, so the frontend can expand it on click — up to the budget
        rest = kids[max_kids:]
        kids = kids[:max_kids]
        mkids = []
        for k in rest:
            if budget[0] <= 0:
                break
            budget[0] -= 1
            mkids.append(_finish_agg(k, max_kids, budget))
        more = {"name": f"· {len(rest)} more", "full": "",
                "n": sum(k["n"] for k in rest), "s": sum(k["s"] for k in rest),
                "r": sum(k["r"] for k in rest), "kids": mkids}
        refs = sum(k["refs"] for k in rest)
        if refs > more["n"]:
            more["refs"] = refs
    out = {"name": n["name"], "full": n["full"], "n": n["n"], "s": n["s"], "r": n["r"]}
    if "pres" in n:
        out["pres"] = n["pres"]
    if n.get("sk"):
        out["sk"] = 1
    if n["refs"] > n["n"]:
        out["refs"] = n["refs"]
    out["kids"] = [_finish_agg(k, max_kids, budget) for k in kids] + ([more] if more else [])
    return out


def _attach_pres(n, pres):
    if n["name"] in pres:
        n["pres"] = len(pres[n["name"]])
    for k in n["kids"].values():
        _attach_pres(k, pres)


# ---------------------------------------------------------------- the walk

def _agg_walk(seeds, adj, nodes, strings, indeg, visited, max_depth, prims=None, pres=None, allowed=None):
    """BFS over the in-set adjacency, folding objects into (label, class)-aggregated
    nodes. seeds: [(oid, aggregate node)]. Each object is shown once (first path wins);
    `refs` counts every in-set inbound reference, so subtrees shared *within* the set
    are detectable (refs > n)."""
    depthcut = []
    stack = []
    for oid, agg in seeds:
        if oid not in nodes or oid in visited or (allowed is not None and oid not in allowed):
            continue
        visited.add(oid)
        agg["n"] += 1
        agg["s"] += nodes[oid]["used"]
        agg["r"] += nodes[oid]["ret"]
        agg["refs"] += indeg.get(oid, 0)
        stack.append((oid, agg, 0, True))
    while stack:
        oid, agg, depth, is_root = stack.pop()
        if depth >= max_depth:
            if adj.get(oid):
                depthcut.append(oid)
            continue
        cls = nodes[oid]["cls"]
        if prims is not None and not cls.endswith("[]"):
            for pname, pval in prims.get(oid, []):
                label = f"{pname}: {pval}"
                child = agg["kids"].setdefault(label, _new_agg(label, "(field)"))
                child["n"] += 1
                if is_root and pres is not None:
                    pres.setdefault(label, set()).add(oid)
        for label, tid, sk in adj.get(oid, []):
            if tid in visited or (allowed is not None and tid not in allowed):
                continue
            visited.add(tid)
            tcls = nodes[tid]["cls"]
            if tcls == "java.lang.String":
                val = strings.get(nodes[tid]["addr"])
                clabel = f'{label}: "{val}"' if val is not None else f"{label}: String"
            else:
                clabel = f"{label}: {_short(tcls)}" if label != "[]" else f"[]: {_short(tcls)}"
            child = agg["kids"].setdefault(clabel, _new_agg(clabel, tcls, sk))
            child["n"] += 1
            child["s"] += nodes[tid]["used"]
            child["r"] += nodes[tid]["ret"]
            child["refs"] += indeg.get(tid, 0)
            stack.append((tid, child, depth + 1, False))
            if is_root and pres is not None:
                pres.setdefault(clabel, set()).add(oid)
    return depthcut


def _anatomy_build(src, full, K, avail, max_depth, max_kids):
    """Full-graph reference tree (complete outbounds, deeper walk, skipped
    fields traversed), `refs` on aggregate nodes, `untracked` grouped by cause,
    and a class-level reference `graph`. One TREE_NODE_BUDGET bounds the fold
    overflow across the whole payload: the main tree finishes first, the
    untracked groups share what remains."""
    budget = [TREE_NODE_BUDGET]
    nodes, addr2id = src["nodes"], src["addr2id"]
    edges, edges_full, elen = src["edges"], src["edgesFull"], src["elen"]
    strings, ids = src["strings"], src["ids"]
    named, allrefs, prims = _split_refs(src)

    def outedges(oid):
        return edges_full.get(oid) or edges.get(oid, [])

    # complete in-set adjacency: named fields (incl. skipped ones like this$0 — they
    # are real holders) for instances, full outbounds for arrays / field-less objects
    adj, indeg = {}, {}
    for oid in nodes:
        cls = nodes[oid]["cls"]
        if cls.endswith("[]") or oid not in named:
            ch = [("[]", t, False) for t in outedges(oid) if t in nodes]
        else:
            ch = named[oid]
        if ch:
            adj[oid] = ch
            for _, t, _ in ch:
                indeg[t] = indeg.get(t, 0) + 1

    root = _new_agg(_short(full), full)
    visited = set()
    pres = {}
    depthcut = _agg_walk([(rid, root) for rid in ids], adj, nodes, strings, indeg,
                         visited, max_depth, prims=prims, pres=pres)
    # root fields pointing outside the retained set (owned by someone else)
    shared = _new_agg("(shared — held by others too)", "(external)")
    for rid in ids:
        if rid not in nodes:
            continue
        for fname, tgt in allrefs.get(rid, []):
            if tgt in addr2id:
                continue
            label = f"{fname}: (shared)"
            child = shared["kids"].setdefault(label, _new_agg(label, "(external)"))
            child["n"] += 1
            pres.setdefault(label, set()).add(rid)
    if shared["kids"]:
        root["kids"][shared["name"]] = shared

    # whatever is still unreachable gets structure too, grouped by *why* it is
    # unreachable. Depth-cut children are always taken as forest seeds: below
    # them sit the deep
    # chains (LinkedHashMap$Entry.after ×700, …) whose members mutually reference
    # each other, so they have no natural forest root.
    leftover = [oid for oid in nodes if oid not in visited]
    untracked = []
    if leftover:
        lset = set(leftover)
        has_parent = set()
        for oid in leftover:
            for _, t, _ in adj.get(oid, []):
                if t in lset:
                    has_parent.add(t)
        trunc_elem, trunc_obj = set(), False
        for aoid in nodes:
            if elen.get(aoid, 0) > len(outedges(aoid)):
                ac = nodes[aoid]["cls"]
                trunc_elem.add(ac[:-2])
                if ac == "java.lang.Object[]":
                    trunc_obj = True
        reason = {}
        for oid in depthcut:
            for _, t, _ in adj.get(oid, []):
                if t in lset:
                    reason.setdefault(t, "beyond the depth limit")
        for oid in leftover:
            if oid in has_parent or oid in reason:
                continue
            c = nodes[oid]["cls"]
            if c in trunc_elem or trunc_obj:
                reason[oid] = "array slots beyond the extraction cap"
            else:
                reason[oid] = "holder outside the extracted set"
        groups = {}
        for oid, why in reason.items():
            groups.setdefault(why, []).append(oid)
        while True:
            if groups:
                why, roots_ = groups.popitem()
            else:
                leftover = [oid for oid in leftover if oid not in visited]
                if not leftover:
                    break
                # pure cycle with no entry point — pick any member as the seed
                why, roots_ = "cyclic reference cluster", [leftover[0]]
            un = _new_agg(f"(held via {why})", "")
            seeds = []
            for oid in roots_:
                if oid not in nodes or oid in visited:
                    continue
                ccls = nodes[oid]["cls"]
                kid = un["kids"].setdefault(ccls, _new_agg(_short(ccls), ccls))
                seeds.append((oid, kid))
            while seeds:
                cut = _agg_walk(seeds, adj, nodes, strings, indeg, visited, max_depth,
                                prims=prims, allowed=lset)
                # depth-cut grandchildren re-seed the same group: deep chains
                # (LinkedHashMap$Entry.after ×700, …) flatten into one class bucket
                # instead of fragmenting across dozens of nested generations
                seeds = []
                for oid in cut:
                    for _, t, _ in adj.get(oid, []):
                        if t in lset and t not in visited:
                            ccls = nodes[t]["cls"]
                            kid = un["kids"].setdefault(ccls, _new_agg(_short(ccls), ccls))
                            seeds.append((t, kid))
            if not un["kids"]:
                continue
            un["n"] = sum(k["n"] for k in un["kids"].values())
            un["s"] = sum(k["s"] for k in un["kids"].values())
            un["r"] = sum(k["r"] for k in un["kids"].values())
            untracked.append({"why": why, "n": un["n"], "s": un["s"], "r": un["r"],
                              "agg": un})
        untracked.sort(key=lambda g: -g["r"])

    _attach_pres(root, pres)
    tree = _finish_agg(root, max_kids, budget)   # the main view gets first claim on the budget
    for g in untracked:
        g["tree"] = _finish_agg(g.pop("agg"), max_kids, budget)

    # class-level reference graph (graph view): nodes = classes present in the
    # retained set (with their retained bytes), links = field/element references.
    # When the reachability pass has run (src["reach"]), node rows carry two
    # extra columns — [cls, n, shallow, ret, rincl, rshared] — and graph.split
    # holds the holder-set split copies; both stay absent for pre-reach
    # extractions (the frontend hides the wedge/split affordances then).
    reach = src.get("reach")
    gidx, gnodes, glinks = {}, [], {}
    for oid, ch in adj.items():
        sc = nodes[oid]["cls"]
        for name, tid, _ in ch:
            k = (sc, name, nodes[tid]["cls"])
            e = glinks.get(k)
            if e is None:
                e = glinks[k] = [0, 0]
            e[0] += 1
            e[1] += nodes[tid]["used"]
    for oid in nodes:
        c = nodes[oid]["cls"]
        i = gidx.get(c)
        if i is None:
            i = len(gnodes)
            gidx[c] = i
            gnodes.append([c, 0, 0, 0])
        gnodes[i][1] += 1
        gnodes[i][2] += nodes[oid]["used"]
        gnodes[i][3] += nodes[oid]["ret"]
    if reach:
        for row in gnodes:
            ri = reach.get(row[0])
            row.extend(ri if ri else (row[3], 0))
    links = sorted(([gidx[s], gidx[t], f, n, b] for (s, f, t), (n, b) in glinks.items()),
                   key=lambda x: -x[4])[:5000]

    graph = {"nodes": gnodes, "links": links}
    if src.get("split"):
        graph["split"] = src["split"]
    return {"tree": tree, "samples": K, "available": avail,
            "roots": root["n"], "untracked": untracked, "fullEdges": src["hasFullEdges"],
            "depth": max_depth, "graph": graph}


# ---------------------------------------------------------------- compare

def _waterfall(rows, top=10):
    """Freed (shrinkers) vs absorbed (growers) shallow movers, with explicit tail sums —
    at the OOM ceiling the two sides must roughly balance, so the tails matter as much
    as the top movers."""
    def pack(lst):
        return ([[r[0], r[6]] for r in lst[:top]],
                sum(r[6] for r in lst[top:]), max(0, len(lst) - top))
    dec = sorted((r for r in rows if r[6] < 0), key=lambda r: r[6])
    inc = sorted((r for r in rows if r[6] > 0), key=lambda r: -r[6])
    ft, fr, fn = pack(dec)
    it, ir, in_ = pack(inc)
    return {"freed": ft, "freedRest": fr, "freedRestN": fn,
            "absorbed": it, "absorbedRest": ir, "absorbedRestN": in_,
            "freedSum": sum(r[6] for r in dec), "absorbedSum": sum(r[6] for r in inc)}


def _flatten_anat(node, prefix, out):
    # lambda-normalize like everywhere else: the hex suffix is a per-run address and
    # would otherwise show up as phantom remove+add pairs in the diff
    name = norm_lambda(node["name"])
    if name.startswith("· "):
        # "· N more" UI fold: transparent in the diff — its kids flatten under the
        # parent path, exactly as if the fold (and its double counting) didn't exist
        for k in node["kids"]:
            _flatten_anat(k, prefix, out)
        return
    path = f"{prefix}/{name}"
    e = out.setdefault(path, [0, 0])
    e[0] += node["s"]
    e[1] += node["r"]
    for k in node["kids"]:
        _flatten_anat(k, path, out)


def _anatomy_diff(a, b):
    """Diff of two anatomy payloads, matched by label path. Values are
    per-sampled-instance averages (tree totals / K), so extractions with different
    sample counts still compare."""
    if not a or not b:
        return None
    fa, fb = {}, {}
    _flatten_anat(a["tree"], "", fa)
    _flatten_anat(b["tree"], "", fb)
    ka, kb = a["samples"], b["samples"]
    rows = []
    for p in set(fa) | set(fb):
        so, ro = fa.get(p, (0, 0))
        sn, rn = fb.get(p, (0, 0))
        ds, dr = sn / kb - so / ka, rn / kb - ro / ka
        if ds or dr:
            rows.append([p, so / ka, sn / kb, ds, ro / ka, rn / kb, dr])
    rows.sort(key=lambda r: -max(abs(r[3]), abs(r[6])))
    return {"samples": [ka, kb], "rows": rows[:400], "total": len(rows)}
