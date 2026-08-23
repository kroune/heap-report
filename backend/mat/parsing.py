"""backend/mat/parsing — raw extract parsing helpers (pure).

CSV row parsers + name/category helpers. The extracts themselves land in the
per-dump analysis.db (db.py) — the old file readers (_analysis_index_build,
_anat_src_load, …) are gone; db.py's readers return the same structures. No
MAT invocation (extract.py), no payload shaping (payloads.py), no caching
(engine.py).
"""
from __future__ import annotations

import csv
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


# ---------------------------------------------------------------- anatomy field helpers

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


def _split_refs(src):
    """Field views of the parsed extraction. Skipped (synthetic) refs (__/this$…)
    stay in the adjacency with their flag (name, tid, sk) — they are real holders;
    allrefs still excludes them."""
    named, allrefs, prims = {}, {}, {}
    for oid, lst in src["refs"].items():
        for name, tgt, sk in lst:
            if not sk:
                allrefs.setdefault(oid, []).append((name, tgt))
            if tgt in src["addr2id"]:
                named.setdefault(oid, []).append((name, src["addr2id"][tgt], sk))
    for oid, lst in src["prims"].items():
        ps = [(n, v) for n, v, sk in lst if not sk]
        if ps:
            prims[oid] = ps
    return named, allrefs, prims
