"""backend/mat/parsing — raw extract files (CSV + sidecars) -> python structures.

Functions take paths (or already-parsed rows) and return tuples/dicts. No MAT
invocation (extract.py), no payload shaping (payloads.py), no caching
(engine.py).
"""
from __future__ import annotations

import csv
import json
import os
import re

LAMBDA_RE = re.compile(r"\$\$Lambda\+0x[0-9a-f]+$")
FIELD_RE = re.compile(r"(ref|[a-z]+(?:\[\])?)\s+([^:\[\]]+?):\s+([^,\]]*)")
SKIP_FIELD = re.compile(r"^(__|_gr_|this\$)")


# ---------------------------------------------------------------- small pure helpers

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
    all merged-family aggregations (first-seen order preserved)."""
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
