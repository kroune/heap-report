"""backend/mat — MatQueryEngine: the MAT-backed core.QueryEngine + local bootstrap.

Mined (read, never imported) from the old flat modules:
  reportdata.py   — all payload builders (stats/trees/classes/composition/anatomy
                    v1+v2/compare) and the CSV parsing
  analyze_dump.py — headless MAT invocation (ParseHeapDump.sh), bootstrap(),
                    analyze_class(): per-query workspaces, 7200 s timeout, OQL shapes
  matindex.py     — restore-compacted-indexes-before-query, replicated in
                    _restore_indexes() (never run MAT against an unrestored dump)
tools/get_mat.py IS imported (pinned MAT resolution is stable infra, per the task).

Deliberate differences from the old code:
  - Payload caches live here and only here, keyed on the source files' mtimes AND
    meta's state; invalidate(dump_id) drops everything cached for a dump. The
    store/kernel calls invalidate() when a dump's data changes (download,
    bootstrap, compact, finished analysis).
  - No on-disk payload caches (the old anat/*_view*.json): only the store writes a
    dump dir. The in-memory payload cache + the separately cached CSV parse cover
    the same re-parse cost.
  - MAT stdout/stderr streams into job.log (the old capture_output buffered up to
    2 h of output in memory), and the per-query workspace is rmtree'd in a finally
    (the old code leaked it on failure).
  - A failed extraction raises -> the ANALYZE job is FAILED with the real error and
    meta.json is never updated for data that does not exist (the old code recorded
    anatSamples even when the extraction produced nothing; the UI then 404'd).
"""
from __future__ import annotations

import csv
import fcntl
import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import zipfile
from collections import deque
from concurrent.futures import ThreadPoolExecutor

from . import core

_HERE = os.path.dirname(os.path.abspath(__file__))          # backend/
_REPO = os.path.dirname(_HERE)                              # repo root
if _REPO not in sys.path:
    sys.path.insert(0, _REPO)
from tools import get_mat   # noqa: E402  pinned MAT resolution (stable infra)

# ---------------------------------------------------------------- constants

HIST_MIN_SHALLOW = 96 * 1024  # classes smaller than this fold into "· other ·" per package (treemap only)
PAGE = 200                    # classes() page size (old serve.py)

LAMBDA_RE = re.compile(r"\$\$Lambda\+0x[0-9a-f]+$")
FIELD_RE = re.compile(r"(ref|[a-z]+(?:\[\])?)\s+([^:\[\]]+?):\s+([^,\]]*)")
SKIP_FIELD = re.compile(r"^(__|_gr_|this\$)")

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

# MAT invocation (analyze_dump.py)
WS = "/tmp/mat-headless-ws"
MAT_TIMEOUT = 7200            # subprocess timeout, kept from the old code
MAX_EDGE = 48
EDGE_FULL_CAP = 1024          # supplementary complete-outbounds extraction for objects with >MAX_EDGE refs
MAX_STRINGS = 400
SAMPLES = 32
IDS_LIMIT = 1_000_000
MAT_JOBS = int(os.environ.get("MAT_JOBS", "2"))   # concurrent MAT JVMs within one job (each can grow to -Xmx10g)
ZSTD = os.environ.get("ZSTD", "zstd")


# ---------------------------------------------------------------- small pure helpers (reportdata.py)

def _read_csv(path):
    with open(path) as f:
        return [r for r in csv.reader(f)]


def norm_lambda(name):
    return LAMBDA_RE.sub("$$Lambda*", name)


def cat_of(cls):
    if cls.startswith("org.gradle"):
        return "gradle"
    if cls.startswith("com.android"):
        return "agp"
    if cls.startswith("org.jetbrains.kotlin"):
        return "kotlin"
    if cls.startswith(("java.", "jdk.", "sun.", "com.sun")) or "." not in cls:
        return "jdk"
    return "other"


def split_pkg(cls):
    if "." not in cls:
        return "(no package)", cls
    pkg, _, name = cls.rpartition(".")
    return pkg, name


def _merge_fams(rows, n):
    """Lambda-family merge: disp -> [n summed columns]. One parameterized path for
    the old _merged/_merged_hist/_merged_dom trio (first-seen order preserved)."""
    fams = {}
    for row in rows:
        disp = norm_lambda(row[0])
        e = fams.get(disp)
        if e is None:
            e = fams[disp] = [0] * n
        for i in range(n):
            e[i] += row[1 + i]
    return fams


# ---------------------------------------------------------------- raw extract parsing

def _parse_hist(path):
    """[(name, objects, shallow)] — complete class histogram."""
    rows = []
    for r in _read_csv(path):
        if len(r) >= 3 and r[0] != "Class Name" and r[1].isdigit():
            rows.append((r[0], int(r[1]), int(r[2])))
    return rows


def _parse_dom(path):
    """[(name, objects, shallow, retained)] — TOP-LEVEL dominators only (MAT export
    limitation: objects dominated by another object roll up into their dominator)."""
    rows = []
    for r in _read_csv(path):
        if len(r) >= 5 and r[0] != "Class Name" and r[1].lstrip("-").isdigit():
            rows.append((r[0], int(r[1]), int(r[2]), int(r[3])))
    return rows


# ---------------------------------------------------------------- analysis index / rs totals

def _analysis_index_build(data_dir, meta):
    """full class name -> {key, comp: bool, anat: [sample counts available]}"""
    classes = meta.get("classes", {})
    rs = meta.get("rs", {})
    anat_samples = meta.get("anatSamples", {})
    anatdir = os.path.join(data_dir, "anat")
    disk, legacy = {}, set()
    if os.path.isdir(anatdir):
        for fn in os.listdir(anatdir):
            m = re.match(r"(.+)_s(\d+)_nodes\.csv$", fn)
            if m:
                disk.setdefault(m.group(1), set()).add(int(m.group(2)))
                continue
            m = re.match(r"(.+)_nodes\.csv$", fn)
            if m and not re.search(r"_s\d+$", m.group(1)):
                legacy.add(m.group(1))   # unsuffixed = original 8-sample extraction
    out = {}
    for key, full in classes.items():
        ks = set(anat_samples.get(key, [])) | disk.get(key, set())
        if key in legacy:
            ks.add(8)
        out[full] = {
            "key": key,
            "comp": key in rs and os.path.exists(os.path.join(data_dir, rs[key])),
            "anat": sorted(ks),
        }
    return out


def _rs_totals_build(data_dir, meta, idx):
    """full class -> (retained shallow total, retained object count, #classes in set).
    The rs CSV is the retained-set histogram and includes the class itself."""
    rs = meta.get("rs", {})
    out = {}
    for full, st in idx.items():
        if not st["comp"]:
            continue
        p = os.path.join(data_dir, rs.get(st["key"], f"rs_{st['key']}.csv"))
        ts = tc = n = 0
        if os.path.exists(p):
            for r in _read_csv(p)[1:]:
                if len(r) >= 3 and r[1].isdigit():
                    tc += int(r[1])
                    ts += int(r[2])
                    n += 1
        out[full] = (ts, tc, n)
    return out


# ---------------------------------------------------------------- payload builders

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


def _composition_build(p):
    rows = []
    for r in _read_csv(p)[1:]:
        if len(r) >= 3 and r[1].isdigit():
            rows.append((r[0], int(r[1]), int(r[2])))
    rows.sort(key=lambda r: -r[2])
    return {"rows": [[c, s, n] for c, n, s in rows[:100]],
            "totalShallow": sum(s for _, _, s in rows),
            "totalObjects": sum(n for _, n, _ in rows),
            "classes": len(rows)}


# ---------------------------------------------------------------- anatomy extracts

def _parse_fields_dump(s):
    """'[ref a:\tnull, boolean b:\tfalse, ref c:\t0x123]' -> [(typ, name, value)]"""
    out = []
    if not s or not s.startswith("["):
        return out
    for m in FIELD_RE.finditer(s[1:]):
        out.append((m.group(1), m.group(2).strip(), m.group(3).strip()))
    return out


def _short(cls):
    return cls.rsplit(".", 1)[-1]


def _anat_srcs(data_dir, key, K):
    """Source files of an anatomy extraction (path math only, no parsing) — used for
    mtime freshness checks. None when the extraction does not exist."""
    anatdir = os.path.join(data_dir, "anat")
    prefix = f"{key}_s{K}_" if os.path.exists(os.path.join(anatdir, f"{key}_s{K}_nodes.csv")) else f"{key}_"
    if not os.path.exists(os.path.join(anatdir, f"{prefix}nodes.csv")):
        return None
    paths = [os.path.join(anatdir, f"{prefix}{k}.csv") for k in ("nodes", "fields", "edges", "edgesfull", "strings")]
    return [*paths, os.path.join(anatdir, f"{key}_s{K}.json"), os.path.join(data_dir, "meta.json")]


def _anat_src_load(data_dir, key, srcs, meta):
    """Parse one anatomy extraction (nodes/fields/edges/strings). Shared by the v1
    and v2 builders — the parse only runs on a payload-cache miss."""
    nodes_p, fields_p, edges_p, efull_p, strings_p, side = srcs[0], srcs[1], srcs[2], srcs[3], srcs[4], srcs[5]
    nodes, addr2id = {}, {}
    for r in _read_csv(nodes_p)[1:]:
        if len(r) >= 5 and r[0].isdigit():
            oid = int(r[0])
            addr = int(r[1], 16) if r[1].startswith("0x") else 0
            nodes[oid] = {"addr": addr, "cls": r[2], "used": int(r[3]), "ret": int(r[4])}
            addr2id[addr] = oid
    # refs/prims keep the SKIP_FIELD flag instead of dropping the fields: v1 filters
    # them out (historic behavior), v2 traverses them (this$0 etc. are real holders)
    refs, prims = {}, {}
    if os.path.exists(fields_p):
        for r in _read_csv(fields_p)[1:]:
            if len(r) >= 2 and r[0].isdigit():
                oid = int(r[0])
                for typ, name, val in _parse_fields_dump(r[1]):
                    sk = bool(SKIP_FIELD.match(name))
                    if typ.startswith("ref"):
                        if val.startswith("0x"):
                            refs.setdefault(oid, []).append((name, int(val, 16), sk))
                    else:
                        prims.setdefault(oid, []).append((name, val, sk))
    edges, edges_full, elen = {}, {}, {}
    if os.path.exists(edges_p):
        for r in _read_csv(edges_p)[1:]:
            if len(r) >= 2 and r[0].isdigit():
                oid = int(r[0])
                elen[oid] = int(r[1]) if r[1].isdigit() else 0
                edges[oid] = [int(x) for x in r[2:] if x.isdigit()]
    has_full = os.path.exists(efull_p)
    if has_full:
        # complete outbounds for objects with >MAX_EDGE refs (supplementary extraction);
        # without it, big-array children beyond slot 48 are invisible in the graph
        for r in _read_csv(efull_p)[1:]:
            if len(r) >= 2 and r[0].isdigit():
                edges_full[int(r[0])] = [int(x) for x in r[2:] if x.isdigit()]
    strings = {}
    if os.path.exists(strings_p):
        for r in _read_csv(strings_p)[1:]:
            if len(r) >= 2 and r[0].startswith("0x"):
                v = r[1].replace("\n", " ").replace("\r", " ")
                strings[int(r[0], 16)] = v[:60] + ("…" if len(v) > 60 else "")
    ids = None
    if os.path.exists(side):
        with open(side) as f:
            ids = json.load(f).get("ids")
    if ids is None:
        ids = meta.get("ids", {}).get(key, [])
    return {"nodes": nodes, "addr2id": addr2id, "refs": refs, "prims": prims,
            "edges": edges, "edgesFull": edges_full, "elen": elen, "hasFullEdges": has_full,
            "strings": strings, "ids": [int(x) for x in ids]}


def _split_refs(src, v2):
    """Field views of the parsed extraction. v1: skipped fields (__/this$…) are
    dropped entirely and named refs are (name, tid) pairs. v2: skipped refs stay in
    the adjacency with their flag (name, tid, sk); allrefs still excludes them."""
    named, allrefs, prims = {}, {}, {}
    for oid, lst in src["refs"].items():
        for name, tgt, sk in lst:
            if not sk:
                allrefs.setdefault(oid, []).append((name, tgt))
            if tgt in src["addr2id"]:
                if v2:
                    named.setdefault(oid, []).append((name, src["addr2id"][tgt], sk))
                elif not sk:
                    named.setdefault(oid, []).append((name, src["addr2id"][tgt]))
    for oid, lst in src["prims"].items():
        ps = [(n, v) for n, v, sk in lst if not sk]
        if ps:
            prims[oid] = ps
    return named, allrefs, prims


def _new_agg(label, cls, sk=False, v2=False):
    """Aggregate tree node. v2 nodes carry `refs` (in-set inbound reference count)
    and the skip flag; v1 nodes must not (load-bearing payload difference)."""
    n = {"name": label, "full": cls, "n": 0, "s": 0, "r": 0, "kids": {}}
    if v2:
        n["refs"] = 0
        if sk:
            n["sk"] = 1
    return n


def _finish_agg(n, max_kids, v2):
    kids = sorted(n["kids"].values(), key=lambda k: (-k["r"], -k["s"]))
    if len(kids) > max_kids:
        rest = kids[max_kids:]
        more = {"name": f"· {len(rest)} more", "full": "",
                "n": sum(k["n"] for k in rest), "s": sum(k["s"] for k in rest),
                "r": sum(k["r"] for k in rest)}
        if v2:
            more["refs"] = sum(k["refs"] for k in rest)
        more["kids"] = {}
        kids = kids[:max_kids] + [more]
    out = {"name": n["name"], "full": n["full"], "n": n["n"], "s": n["s"], "r": n["r"]}
    if "pres" in n:
        out["pres"] = n["pres"]
    if v2:
        if n.get("sk"):
            out["sk"] = 1
        if n["refs"] > n["n"]:
            out["refs"] = n["refs"]
    out["kids"] = [_finish_agg(k, max_kids, v2) for k in kids]
    return out


def _attach_pres(n, pres):
    if n["name"] in pres:
        n["pres"] = len(pres[n["name"]])
    for k in n["kids"].values():
        _attach_pres(k, pres)


def _anatomy_build(src, full, K, avail, max_depth, max_kids):
    """v1: aggregated named-reference tree. Depth-1 children carry `pres` = in how
    many of the K samples the field was non-null."""
    nodes, addr2id = src["nodes"], src["addr2id"]
    named, allrefs, prims = _split_refs(src, v2=False)
    edges, strings = src["edges"], src["strings"]

    root = _new_agg(_short(full), full)
    visited = set()
    stack = []
    pres = {}   # depth-1 label -> set of root ids where the field is non-null
    shared = _new_agg("(shared — held by others too)", "(external)")
    for rid in src["ids"]:
        if rid not in nodes or rid in visited:
            continue
        visited.add(rid)
        root["n"] += 1
        root["s"] += nodes[rid]["used"]
        root["r"] += nodes[rid]["ret"]
        stack.append((rid, root, 0, True))
    while stack:
        oid, agg, depth, is_root = stack.pop()
        if is_root:
            # true presence: field non-null in the instance, regardless of whether
            # the target is inside the retained set (shared targets would otherwise
            # vanish — the 'missing resolutionStrategy' trap)
            for fname, tgt in allrefs.get(oid, []):
                if tgt in addr2id:
                    continue   # handled as a regular child below
                label = f"{fname}: (shared)"
                child = shared["kids"].setdefault(label, _new_agg(label, "(external)"))
                child["n"] += 1
                pres.setdefault(label, set()).add(oid)
        if depth >= max_depth:
            continue
        cls = nodes[oid]["cls"]
        if cls.endswith("[]") or oid not in named:
            children = [("[]", t) for t in edges.get(oid, [])]
        else:
            children = named[oid]
            for pname, pval in prims.get(oid, []):
                label = f"{pname}: {pval}"
                child = agg["kids"].setdefault(label, _new_agg(label, "(field)"))
                child["n"] += 1
                if is_root:
                    pres.setdefault(label, set()).add(oid)
        for label, tid in children:
            if tid not in nodes or tid in visited:
                continue
            visited.add(tid)
            tcls = nodes[tid]["cls"]
            if tcls == "java.lang.String":
                val = strings.get(nodes[tid]["addr"])
                clabel = f'{label}: "{val}"' if val is not None else f"{label}: String"
            else:
                clabel = f"{label}: {_short(tcls)}" if label != "[]" else f"[]: {_short(tcls)}"
            child = agg["kids"].setdefault(clabel, _new_agg(clabel, tcls))
            child["n"] += 1
            child["s"] += nodes[tid]["used"]
            child["r"] += nodes[tid]["ret"]
            if tcls != "java.lang.String":
                stack.append((tid, child, depth + 1, False))
            if is_root:
                pres.setdefault(clabel, set()).add(oid)
    if shared["kids"]:
        root["kids"][shared["name"]] = shared
    leftover = [oid for oid in nodes if oid not in visited]
    if leftover:
        un = _new_agg("(held via untracked/shared refs)", "")
        for oid in leftover:
            un["n"] += 1
            un["s"] += nodes[oid]["used"]
            un["r"] += nodes[oid]["ret"]
            ccls = nodes[oid]["cls"]
            child = un["kids"].setdefault(ccls, _new_agg(_short(ccls), ccls))
            child["n"] += 1
            child["s"] += nodes[oid]["used"]
            child["r"] += nodes[oid]["ret"]
        root["kids"]["(held via untracked/shared refs)"] = un

    _attach_pres(root, pres)
    return {"tree": _finish_agg(root, max_kids, v2=False), "samples": K,
            "available": avail, "roots": root["n"]}


# ---------------------------------------------------------------- anatomy v2 (full graph)

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
                child = agg["kids"].setdefault(label, _new_agg(label, "(field)", v2=True))
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
            child = agg["kids"].setdefault(clabel, _new_agg(clabel, tcls, sk, v2=True))
            child["n"] += 1
            child["s"] += nodes[tid]["used"]
            child["r"] += nodes[tid]["ret"]
            child["refs"] += indeg.get(tid, 0)
            stack.append((tid, child, depth + 1, False))
            if is_root and pres is not None:
                pres.setdefault(clabel, set()).add(oid)
    return depthcut


def _anat2_build(src, full, K, avail, max_depth, max_kids):
    """v2: full-graph reference tree (complete outbounds, deeper walk, skipped
    fields traversed), `refs` on aggregate nodes, `untracked` grouped by cause,
    and a class-level reference `graph`."""
    nodes, addr2id = src["nodes"], src["addr2id"]
    edges, edges_full, elen = src["edges"], src["edgesFull"], src["elen"]
    strings, ids = src["strings"], src["ids"]
    named, allrefs, prims = _split_refs(src, v2=True)

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

    root = _new_agg(_short(full), full, v2=True)
    visited = set()
    pres = {}
    depthcut = _agg_walk([(rid, root) for rid in ids], adj, nodes, strings, indeg,
                         visited, max_depth, prims=prims, pres=pres)
    # root fields pointing outside the retained set (owned by someone else)
    shared = _new_agg("(shared — held by others too)", "(external)", v2=True)
    for rid in ids:
        if rid not in nodes:
            continue
        for fname, tgt in allrefs.get(rid, []):
            if tgt in addr2id:
                continue
            label = f"{fname}: (shared)"
            child = shared["kids"].setdefault(label, _new_agg(label, "(external)", v2=True))
            child["n"] += 1
            pres.setdefault(label, set()).add(rid)
    if shared["kids"]:
        root["kids"][shared["name"]] = shared

    # whatever is still unreachable gets structure too, grouped by *why* it is
    # unreachable — this replaces v1's flat "held via untracked/shared refs" bucket.
    # Depth-cut children are always taken as forest seeds: below them sit the deep
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
            un = _new_agg(f"(held via {why})", "", v2=True)
            seeds = []
            for oid in roots_:
                if oid not in nodes or oid in visited:
                    continue
                ccls = nodes[oid]["cls"]
                kid = un["kids"].setdefault(ccls, _new_agg(_short(ccls), ccls, v2=True))
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
                            kid = un["kids"].setdefault(ccls, _new_agg(_short(ccls), ccls, v2=True))
                            seeds.append((t, kid))
            if not un["kids"]:
                continue
            un["n"] = sum(k["n"] for k in un["kids"].values())
            un["s"] = sum(k["s"] for k in un["kids"].values())
            un["r"] = sum(k["r"] for k in un["kids"].values())
            untracked.append({"why": why, "n": un["n"], "s": un["s"], "r": un["r"],
                              "tree": _finish_agg(un, max_kids, v2=True)})
        untracked.sort(key=lambda g: -g["r"])

    _attach_pres(root, pres)

    # class-level reference graph (graph view): nodes = classes present in the
    # retained set (with their retained bytes), links = field/element references
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
    links = sorted(([gidx[s], gidx[t], f, n, b] for (s, f, t), (n, b) in glinks.items()),
                   key=lambda x: -x[4])[:5000]

    return {"tree": _finish_agg(root, max_kids, v2=True), "samples": K, "available": avail,
            "roots": root["n"], "untracked": untracked, "fullEdges": src["hasFullEdges"],
            "depth": max_depth, "graph": {"nodes": gnodes, "links": links}}


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
    name = LAMBDA_RE.sub("$$Lambda*", node["name"])
    path = f"{prefix}/{name}"
    e = out.setdefault(path, [0, 0])
    e[0] += node["s"]
    e[1] += node["r"]
    for k in node["kids"]:
        _flatten_anat(k, path, out)


def _anatomy_diff(a, b):
    """Diff of two v1 anatomy payloads, matched by label path. Values are
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


# ---------------------------------------------------------------- MAT invocation helpers

def suffix(tag, key):
    """MAT truncates -filename_suffix at 20 chars, so long keys need hashing."""
    s = f"{tag}_{key}"
    if len(s) > 20:
        s = f"{tag}_{key[:6]}_{hashlib.sha1(key.encode()).hexdigest()[:5]}"
    return s[:20]


def subselect(cls, ids):
    cond = " or ".join(f"s.@objectId = {i}" for i in ids)
    return f"SELECT AS RETAINED SET * FROM INSTANCEOF {cls} s WHERE {cond}"


def sample_even(ids, k):
    """k evenly-spaced picks spanning the whole id list (kills first-N-by-address bias)."""
    if len(ids) <= k:
        return ids
    if k <= 1:
        return ids[:1]
    step = (len(ids) - 1) / (k - 1)
    out, seen = [], set()
    for i in range(k):
        v = ids[round(i * step)]
        if v not in seen:
            seen.add(v)
            out.append(v)
    return out


def _par(fns):
    """Overlap independent zero-arg callables (they just wait on MAT subprocesses),
    MAT JVM concurrency bounded by MAT_JOBS — each JVM can grow to -Xmx10g.
    The first failure raises (the old code logged and yielded None, which is how
    failed extractions got recorded as successes)."""
    with ThreadPoolExecutor(max_workers=max(1, min(MAT_JOBS, len(fns)))) as ex:
        return list(ex.map(lambda f: f(), fns))


def _decompress_one(zst):
    """zstd -d one compacted index; restored raw mtime := zst mtime (the convention
    that lets re-compact drop untouched raws for free)."""
    raw = zst[:-4]
    tmp = f"{raw}.tmp{os.getpid()}"
    try:
        r = subprocess.run(["nice", "-n", "10", ZSTD, "-d", "-T4", "-q", "-f",
                            "-o", tmp, "--", zst], capture_output=True, text=True)
        if r.returncode != 0:
            raise RuntimeError(f"zstd -d {zst} failed:\n{r.stderr[-500:]}")
        os.replace(tmp, raw)
        ns = os.stat(zst).st_mtime_ns
        os.utime(raw, ns=(ns, ns))
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)


# ---------------------------------------------------------------- the engine

class MatQueryEngine:
    """core.QueryEngine over the local dump store, plus the local MAT bootstrap
    (LocalIndexer role). All dump-dir paths come from store.dir_of (READY-only);
    all meta.json writes go through store.update_meta (single writer)."""

    def __init__(self, store: core.LocalDumpStore, jobs: core.JobRegistry):
        self._store = store
        self._jobs = jobs
        self._lock = threading.RLock()
        self._cache = {}            # key tuple -> (fingerprint, payload)
        self._mat = None            # resolved ParseHeapDump.sh path
        self._mat_lock = threading.Lock()

    # ---------------------------------------------------------- plumbing

    def _data_dir(self, dump_id):
        """Query-side entry: READY only (dir_of also hands out INDEXING dirs to
        the store-sanctioned bootstrap job — that path never goes through here)."""
        info = self._store.get(dump_id)
        if info.state is not core.DumpState.READY:
            raise core.ApiError("bad_state",
                                f"dump {dump_id} is {info.state.value}, queries need ready", 409)
        return os.path.join(self._store.dir_of(dump_id), "data")

    def _meta(self, dump_id):
        return self._store.read_meta(dump_id)

    def _fp(self, dump_id, paths):
        """Cache fingerprint: newest mtime of the underlying files + meta's state."""
        mt = 0
        for p in paths:
            try:
                mt = max(mt, os.stat(p).st_mtime_ns)
            except OSError:
                pass
        try:
            state = self._meta(dump_id).get("state", "")
        except Exception:   # noqa: BLE001 - a meta read hiccup must not break queries
            state = ""
        return (mt, state)

    def _cached(self, key, fp, loader):
        with self._lock:
            ent = self._cache.get(key)
            if ent is not None and ent[0] == fp:
                return ent[1]
        val = loader()
        with self._lock:
            self._cache[key] = (fp, val)
        return val

    def invalidate(self, dump_id=None):
        """Drop cached payloads for one dump (or all). The store/kernel calls this
        when a dump's data changes: download, bootstrap, compact, finished analysis.
        The engine also calls it itself after a successful analyze/bootstrap job."""
        with self._lock:
            if dump_id is None:
                self._cache.clear()
            else:
                for k in [k for k in self._cache if dump_id in k]:
                    del self._cache[k]

    # ---------------------------------------------------------- cached raw loaders

    def _load_hist(self, dump_id):
        p = os.path.join(self._data_dir(dump_id), "histogram.csv")
        return self._cached((dump_id, "hist"), self._fp(dump_id, [p]),
                            lambda: _parse_hist(p))

    def _load_dom(self, dump_id):
        p = os.path.join(self._data_dir(dump_id), "dominator_by_class.csv")
        return self._cached((dump_id, "dom"), self._fp(dump_id, [p]),
                            lambda: _parse_dom(p))

    def _analysis_index(self, dump_id):
        data = self._data_dir(dump_id)
        mp = os.path.join(data, "meta.json")
        return self._cached((dump_id, "anaidx"), self._fp(dump_id, [mp]),
                            lambda: _analysis_index_build(data, self._meta(dump_id)))

    def _rs_totals(self, dump_id):
        data = self._data_dir(dump_id)
        mp = os.path.join(data, "meta.json")
        return self._cached((dump_id, "rstot"), self._fp(dump_id, [mp]),
                            lambda: _rs_totals_build(data, self._meta(dump_id),
                                                     self._analysis_index(dump_id)))

    # ---------------------------------------------------------- queries

    def trees(self, dump_id):
        data = self._data_dir(dump_id)
        paths = [os.path.join(data, n) for n in
                 ("histogram.csv", "dominator_by_class.csv", "meta.json")]
        return self._cached(
            (dump_id, "trees"), self._fp(dump_id, paths),
            lambda: {"stats": _stats_build(self._load_hist(dump_id), self._load_dom(dump_id),
                                           self._meta(dump_id), self._analysis_index(dump_id)),
                     "trees": _trees_build(self._load_hist(dump_id), self._load_dom(dump_id))})

    def _class_table(self, dump_id):
        data = self._data_dir(dump_id)
        paths = [os.path.join(data, n) for n in ("histogram.csv", "meta.json")]
        return self._cached((dump_id, "classtable"), self._fp(dump_id, paths),
                            lambda: _class_table_build(self._load_hist(dump_id),
                                                       self._analysis_index(dump_id),
                                                       self._rs_totals(dump_id)))

    def classes(self, dump_id, filter="", sort="-s", page=0):
        rows = list(self._class_table(dump_id))   # copy: filter/sort must not touch the cache
        filt = filter.strip().lower()
        if filt:
            rows = [r for r in rows if filt in r["disp"].lower()]
        keymap = {
            "s": lambda r: r["s"], "c": lambda r: r["c"], "pi": lambda r: r["pi"],
            "r": lambda r: (r["r"] is not None, r["r"] or 0), "name": lambda r: r["disp"].lower(),
        }
        rev = sort.startswith("-")
        key = keymap.get(sort.lstrip("-"), keymap["s"])
        if sort.lstrip("-") == "r":   # unanalyzed (r=None) always last
            rows.sort(key=lambda r: (r["r"] is None, -(r["r"] or 0)))
        else:
            rows.sort(key=key, reverse=rev)
        page = max(0, page)
        total = len(rows)
        start = page * PAGE
        return {"rows": rows[start:start + PAGE], "total": total,
                "page": page, "pages": max(1, (total + PAGE - 1) // PAGE)}

    def composition(self, dump_id, cls):
        """Retained-set composition ("what's inside") for an analyzed class.
        None = not analyzed yet."""
        data = self._data_dir(dump_id)
        st = self._analysis_index(dump_id).get(cls)
        if not st or not st["comp"]:
            return None
        meta = self._meta(dump_id)
        p = os.path.join(data, meta.get("rs", {}).get(st["key"], f"rs_{st['key']}.csv"))
        if not os.path.exists(p):
            return None
        return self._cached((dump_id, "comp", st["key"]),
                            self._fp(dump_id, [p, os.path.join(data, "meta.json")]),
                            lambda: _composition_build(p))

    def _anat_src(self, dump_id, key, K):
        data = self._data_dir(dump_id)
        srcs = _anat_srcs(data, key, K)
        if not srcs:
            return None
        return self._cached((dump_id, "anatsrc", key, K), self._fp(dump_id, srcs),
                            lambda: _anat_src_load(data, key, srcs, self._meta(dump_id)))

    def anatomy(self, dump_id, cls, version=1, samples=None):
        """version 1 = old anatomy(), 2 = old anat2(). None = not analyzed."""
        data = self._data_dir(dump_id)
        st = self._analysis_index(dump_id).get(cls)
        if not st or not st["anat"]:
            return None
        key = st["key"]
        avail = st["anat"]
        K = samples if samples in avail else avail[-1]
        if not _anat_srcs(data, key, K):
            return None
        build, max_depth, max_kids = (_anat2_build, 32, 40) if version == 2 \
            else (_anatomy_build, 14, 40)

        def load():
            src = self._anat_src(dump_id, key, K)
            return None if src is None else build(src, cls, K, avail, max_depth, max_kids)

        return self._cached((dump_id, "anat", version, key, K),
                            self._fp(dump_id, _anat_srcs(data, key, K)), load)

    def compare(self, a, b):
        """Not payload-cached at this level (the old compare_payload wasn't either):
        it assembles cheap merges over the per-dump caches, which carry freshness."""
        da, db = self._data_dir(a), self._data_dir(b)
        A, B = _merge_fams(self._load_hist(a), 2), _merge_fams(self._load_hist(b), 2)
        rows = []
        for k in set(A) | set(B):
            co, so = A.get(k, (0, 0))
            cn, sn = B.get(k, (0, 0))
            rows.append([k, co, cn, cn - co, so, sn, sn - so])
        # top-level dominator deltas (owned memory — closer to cause than shallow histogram)
        DA = _merge_fams(self._load_dom(a), 3)
        DB = _merge_fams(self._load_dom(b), 3)
        dom = []
        for k in set(DA) | set(DB):
            oo, so, ro = DA.get(k, (0, 0, 0))
            on, sn, rn = DB.get(k, (0, 0, 0))
            dom.append([k, oo, on, so, sn, ro, rn, rn - ro])
        dom.sort(key=lambda r: -abs(r[7]))
        # retained deltas for classes analyzed in both dumps
        ta, tb = self._rs_totals(a), self._rs_totals(b)
        retained = []
        for full in sorted(set(ta) & set(tb)):
            retained.append([full, ta[full][0], tb[full][0], tb[full][0] - ta[full][0]])
        retained.sort(key=lambda r: -abs(r[3]))
        proxies = []
        for p in PROXIES:
            co, so = A.get(p, (0, 0))
            cn, sn = B.get(p, (0, 0))
            proxies.append([p, co, cn, so, sn])
        # composition availability per dump — drives the rs-diff drill-down buttons
        ia, ib = self._analysis_index(a), self._analysis_index(b)
        analyzed = {"old": sorted(f for f, st in ia.items() if st["comp"]),
                    "new": sorted(f for f, st in ib.items() if st["comp"])}
        # anatomy diffs for classes with an extraction in both dumps
        anats = {}
        for full in sorted(set(ia) & set(ib)):
            if ia[full]["anat"] and ib[full]["anat"]:
                d = _anatomy_diff(self.anatomy(a, full), self.anatomy(b, full))
                if d:
                    anats[full] = d
        return {
            "old": _stats_build(self._load_hist(a), self._load_dom(a), self._meta(a), ia),
            "new": _stats_build(self._load_hist(b), self._load_dom(b), self._meta(b), ib),
            "rows": rows,
            "dom": dom,
            "waterfall": _waterfall(rows),
            "retained": retained,
            "proxies": proxies,
            "analyzed": analyzed,
            "anats": anats,
        }

    # ---------------------------------------------------------- MAT invocation

    def _ensure_mat(self, log):
        """MAT must exist before any query runs; download it on first use."""
        with self._mat_lock:
            if self._mat and os.path.exists(self._mat):
                return self._mat
            p = os.environ.get("MAT_PARSE", get_mat.parse_sh())
            if not os.path.exists(p):
                p = get_mat.ensure(log=log)
            self._mat = p
            return p

    def _restore_indexes(self, dump_dir, log):
        """zstd -d every *.index.zst whose raw file is missing (matindex.py's restore,
        replicated — MAT must never run against a compacted dump unrestored). No-op
        fast path when nothing is missing."""
        todo = [z for z in sorted(glob.glob(os.path.join(dump_dir, "*.index.zst")))
                if not os.path.exists(z[:-4])]
        if not todo:
            return
        f = open(os.path.join(dump_dir, ".matindex.lock"), "a")
        fcntl.flock(f, fcntl.LOCK_EX)
        try:
            todo = [z for z in todo if not os.path.exists(z[:-4])]   # recheck under lock
            if not todo:
                return
            total = sum(os.path.getsize(z) for z in todo)
            log(f"  restoring {len(todo)} compacted MAT index files "
                f"({total / 1e9:.1f} GB compressed) ...")
            with ThreadPoolExecutor(max_workers=min(4, len(todo))) as ex:
                list(ex.map(_decompress_one, todo))   # raises the first error
        finally:
            f.close()

    def _run_mat(self, job, hprof, outdir, sfx, command, keep_name, limit=2000000):
        """Run one MAT headless query; move the resulting CSV to outdir/keep_name.
        Resumable (existing dst short-circuits). Raises RuntimeError with the MAT
        output tail on any failure — never returns None for a failed extraction."""
        dst = os.path.join(outdir, keep_name)
        if os.path.exists(dst):
            return dst
        log = lambda m: job.log.append(m)
        mat = self._ensure_mat(log)
        # a compacted dump (indexes stored as *.index.zst) is restored on demand
        self._restore_indexes(os.path.dirname(os.path.abspath(hprof)), log)
        # unique workspace per query: concurrent MAT instances can't share one
        # (Eclipse .lock), and the old single shared WS collided across runs
        ws = f"{WS}-{os.getpid()}-{sfx}"
        cmd = [mat, "-data", ws, hprof, f"-command={command}", "-format=csv",
               f"-limit={limit}", f"-filename_suffix={sfx}", "org.eclipse.mat.api:query"]
        log(f"  MAT {keep_name} ...")
        tail = deque(maxlen=50)
        proc = None
        try:
            proc = subprocess.Popen(cmd, cwd=_REPO, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, text=True)

            def drain():   # stream MAT output into the job log, don't buffer it whole
                for line in proc.stdout:
                    line = line.rstrip("\n")
                    tail.append(line)
                    job.log.append(line)

            t = threading.Thread(target=drain, daemon=True)
            t.start()
            try:
                proc.wait(timeout=MAT_TIMEOUT)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()
                raise RuntimeError(f"MAT query {sfx} timed out after {MAT_TIMEOUT}s")
            t.join()
        finally:
            shutil.rmtree(ws, ignore_errors=True)   # always — the old code leaked ws on failure
        base = os.path.splitext(os.path.basename(hprof))[0]
        # MAT writes the report zip next to the hprof, not into cwd.
        z = os.path.join(os.path.dirname(hprof), f"{base}_{sfx}.zip")
        if not os.path.exists(z):
            raise RuntimeError(f"MAT query {sfx} failed (rc={proc.returncode}):\n"
                               + "\n".join(list(tail)[-20:]))
        tmp = f"/tmp/qout/{os.getpid()}-{sfx}"
        shutil.rmtree(tmp, ignore_errors=True)
        os.makedirs(tmp, exist_ok=True)
        try:
            with zipfile.ZipFile(z) as zf:
                zf.extractall(tmp)
            csvs = sorted(glob.glob(os.path.join(tmp, "pages", "*.csv")))
        finally:
            if os.path.exists(z):
                os.remove(z)
        if not csvs:
            shutil.rmtree(tmp, ignore_errors=True)
            raise RuntimeError(f"MAT query {sfx} produced no CSV")
        if len(csvs) > 1:
            log(f"WARNING: {sfx} produced {len(csvs)} CSV pages; keeping "
                f"{os.path.basename(csvs[0])} — result may be truncated")
        shutil.move(csvs[0], dst)
        shutil.rmtree(tmp, ignore_errors=True)
        return dst

    def _hprof(self, dump_id):
        """The hprof lives inside the dump dir, next to data/ and the MAT indexes."""
        dump_dir = self._store.dir_of(dump_id)
        meta = self._meta(dump_id)
        p = os.path.join(dump_dir, meta.get("dump", ""))
        if meta.get("dump") and os.path.exists(p):
            return p
        hits = glob.glob(os.path.join(dump_dir, "*.hprof"))
        return hits[0] if hits else None

    def _alloc_key(self, dump_id, full):
        """Stable short key for a class, collision-free against meta."""
        classes = self._meta(dump_id).get("classes", {})
        for k, v in classes.items():
            if v == full:
                return k
        base = re.sub(r"[^A-Za-z0-9_]", "_", full)[-60:] or "cls"
        key, i = base, 2
        while key in classes:
            key = f"{base}_{i}"
            i += 1
        return key

    # ---------------------------------------------------------- analyze

    def analyze(self, dump_id, cls, samples=SAMPLES, with_anatomy=True):
        """Queue on-demand per-class MAT analysis (old analyze_class). The class is
        validated against the histogram first; a failed extraction surfaces as a
        FAILED job with the real error and is never recorded in meta.json."""
        try:
            samples = max(1, min(1024, int(samples)))
        except (TypeError, ValueError):
            samples = SAMPLES
        known = {name for name, _, _ in self._load_hist(dump_id)}
        if cls not in known:
            raise core.ApiError("not_found", f"class not in histogram: {cls}", 404)
        hprof = self._hprof(dump_id)
        if not hprof:
            raise core.ApiError("bad_state", "hprof not found in the dump dir", 400)

        def fn(job):
            self._analyze_class(job, dump_id, hprof, cls, samples, with_anatomy)

        return self._jobs.submit(core.JobKind.ANALYZE, dump_id, cls, fn)

    def _analyze_class(self, job, dump_id, hprof, cls, samples, with_anatomy):
        """Retained-set composition + (optionally) reference-tree anatomy for one
        class. Resumable: existing CSVs are reused, so escalating to more samples
        only runs the missing extractions."""
        data = self._data_dir(dump_id)
        anat = os.path.join(data, "anat")
        os.makedirs(anat, exist_ok=True)
        key = self._alloc_key(dump_id, cls)
        log = lambda m: job.log.append(m)
        log(f"analyzing {cls} (key={key}, samples={samples}, anatomy={with_anatomy})")
        # retained-set composition and the full id list are independent — overlap them
        tasks = [lambda: self._run_mat(job, hprof, data, suffix("rs", key),
                                       f"show_retained_set {cls}", f"rs_{key}.csv")]
        if with_anatomy:
            tasks.append(lambda: self._run_mat(job, hprof, data, suffix("idsall", key),
                                               f'oql "SELECT s.@objectId FROM INSTANCEOF {cls} s"',
                                               f"idsall_{key}.csv", limit=IDS_LIMIT))
        res = _par(tasks)
        if not res[0] or not os.path.exists(res[0]):
            raise RuntimeError("retained-set query failed")

        def record_rs(m):
            m.setdefault("classes", {})[key] = cls
            m.setdefault("rs", {})[key] = f"rs_{key}.csv"

        self._store.update_meta(dump_id, record_rs)
        if not with_anatomy:
            self.invalidate(dump_id)
            return
        # full id list -> evenly-spaced sample of K instances
        ids = []
        p = res[1]
        if p and os.path.exists(p):
            ids = [r[0] for r in _read_csv(p)[1:] if r and r[0].isdigit()]
        picked = sample_even(ids, samples)
        if not picked:
            raise RuntimeError("no instance ids")
        K = len(picked)
        # per-extraction sidecar so the anatomy can be rebuilt with the same ids
        with open(os.path.join(anat, f"{key}_s{K}.json"), "w") as f:
            json.dump({"key": key, "cls": cls, "samples": K, "ids": picked}, f)
        idx = ", ".join(f"outbounds(o)[{i}]" for i in range(MAX_EDGE))
        idx_full = ", ".join(f"outbounds(o)[{i}]" for i in range(EDGE_FULL_CAP))
        sub = subselect(cls, picked)
        # nodes / edges / fields scan the same retained set independently — run
        # concurrently. edgesfull captures complete outbounds for objects with
        # >MAX_EDGE refs (the plain edges query truncates at MAX_EDGE, which
        # silently orphans big-array children).
        _par([
            lambda: self._run_mat(job, hprof, anat, suffix("n2", f"{key}_s{K}"),
                    f'oql "SELECT o.@objectId, toHex(o.@objectAddress), classof(o).@name, o.@usedHeapSize, o.@retainedHeapSize FROM OBJECTS ({sub}) o"',
                    f"{key}_s{K}_nodes.csv"),
            lambda: self._run_mat(job, hprof, anat, suffix("e2", f"{key}_s{K}"),
                    f'oql "SELECT o.@objectId, outbounds(o).length, {idx} FROM OBJECTS ({sub}) o"',
                    f"{key}_s{K}_edges.csv"),
            lambda: self._run_mat(job, hprof, anat, suffix("f2", f"{key}_s{K}"),
                    f'oql "SELECT o.@objectId, o.getFields() FROM OBJECTS ({sub}) o WHERE o implements org.eclipse.mat.snapshot.model.IInstance"',
                    f"{key}_s{K}_fields.csv"),
            lambda: self._run_mat(job, hprof, anat, suffix("ef", f"{key}_s{K}"),
                    f'oql "SELECT o.@objectId, outbounds(o).length, {idx_full} FROM OBJECTS ({sub}) o WHERE outbounds(o).length > {MAX_EDGE}"',
                    f"{key}_s{K}_edgesfull.csv"),
        ])
        nodes_p = os.path.join(anat, f"{key}_s{K}_nodes.csv")
        if not os.path.exists(nodes_p):
            raise RuntimeError(f"anatomy extraction failed: {key}_s{K}_nodes.csv missing")
        strings_dst = os.path.join(anat, f"{key}_s{K}_strings.csv")
        if not os.path.exists(strings_dst):
            addrs = [r[1] for r in _read_csv(nodes_p)[1:]
                     if len(r) >= 5 and r[2] == "java.lang.String"][:MAX_STRINGS]
            if addrs:
                self._run_mat(job, hprof, anat, suffix("s2", f"{key}_s{K}"),
                        f'oql "SELECT toHex(o.@objectAddress), toString(o) FROM OBJECTS {",".join(addrs)} o"',
                        f"{key}_s{K}_strings.csv")

        def record_anat(m):
            ks = set(m.get("anatSamples", {}).get(key, []))
            ks.add(K)
            m.setdefault("anatSamples", {})[key] = sorted(ks)
            m.setdefault("ids", {})[key] = picked

        self._store.update_meta(dump_id, record_anat)
        self.invalidate(dump_id)
        log(f"done: {cls} ({K} samples)")

    # ---------------------------------------------------------- bootstrap (LocalIndexer)

    def submit_bootstrap(self, dump_id):
        """Submit an INDEX job running the local MAT bootstrap (histogram +
        dominators). On success the dump flips to "ready", on failure to "failed"
        with the error — both via store.update_meta. The kernel wires this to the
        store's INDEXING transition."""

        def fn(job):
            try:
                self._bootstrap(job, dump_id)
            except Exception as e:   # noqa: BLE001 - record, then let the job FAIL
                def fail(m, err=str(e)):
                    m["state"] = core.DumpState.FAILED.value
                    m["error"] = err

                try:
                    self._store.update_meta(dump_id, fail)
                except Exception as me:   # noqa: BLE001 - don't mask the real error
                    job.log.append(f"WARNING: could not record failure in meta: {me}")
                raise

            def ok(m):
                m["state"] = core.DumpState.READY.value
                m["error"] = None

            self._store.update_meta(dump_id, ok)

        return self._jobs.submit(core.JobKind.INDEX, dump_id, "bootstrap", fn)

    def _bootstrap(self, job, dump_id):
        """Global extracts for one dump -> <dump>/data/. Resumable.

        NOTE (integration): the dump is INDEXING, not READY, while this runs, so
        store.dir_of must hand out the dir to the INDEX job here — READY-only
        enforcement applies to the query methods above."""
        dump_dir = self._store.dir_of(dump_id)
        data = os.path.join(dump_dir, "data")
        os.makedirs(data, exist_ok=True)
        hprof = self._hprof(dump_id)
        if not hprof:
            raise RuntimeError("no .hprof in the dump dir")
        log = lambda m: job.log.append(m)
        log(f"analyzing {hprof} -> {dump_dir} (mat-jobs={MAT_JOBS})")

        # the histogram runs first and alone: when the MAT indexes are missing the
        # first query triggers the full hprof parse, and concurrent parsers would
        # clobber each other's index files
        self._run_mat(job, hprof, data, "histogram", "histogram", "histogram.csv")
        log("  histogram done (parse complete)")

        # dominator groupings are independent — run in parallel
        _par([
            lambda: self._run_mat(job, hprof, data, "domclass", "dominator_tree -groupBy BY_CLASS",
                                  "dominator_by_class.csv"),
            lambda: self._run_mat(job, hprof, data, "dompkg", "dominator_tree -groupBy BY_PACKAGE",
                                  "dominator_by_package.csv"),
        ])

        # module count = DefaultScriptHandler instances (fallback 3010)
        modules = 3010
        domp = os.path.join(data, "dominator_by_class.csv")
        if os.path.exists(domp):
            for r in _read_csv(domp)[1:]:
                if len(r) >= 4 and r[0].endswith("DefaultScriptHandler_Decorated") and r[1].isdigit():
                    modules = int(r[1])

        def record(m):
            m["modules"] = modules   # merge — don't clobber prior on-demand analyses
            m["dump"] = os.path.basename(hprof)

        self._store.update_meta(dump_id, record)
        self.invalidate(dump_id)
        log(f"data: {data}")
