#!/usr/bin/env python3
"""Shared data layer for the heap-report app: MAT CSV extracts -> JSON payloads.

Used by serve.py (interactive UI) and generate.py (static snapshot export).
All loaders are cached keyed on file mtime; analyze jobs bump mtimes and thus
invalidate automatically. Lambda classes (`Foo$$Lambda+0x...`) are merged into
`Foo$$Lambda*` families everywhere — the hex suffix is a per-run address and
otherwise produces phantom add/remove noise when comparing dumps.
"""
import csv, json, os, re, threading

HERE = os.path.dirname(os.path.abspath(__file__))   # repo root
REPORT_ROOT = os.environ.get("HEAP_REPORT_DUMPS", os.path.join(HERE, "dumps"))
HIST_MIN_SHALLOW = 96 * 1024  # classes smaller than this fold into "· other ·" per package (treemap only)

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

_lock = threading.RLock()
_cache = {}


def _mtime(path):
    try:
        return os.path.getmtime(path)
    except OSError:
        return 0


def _cached(key, path, loader):
    with _lock:
        ent = _cache.get(key)
        mt = _mtime(path)
        if ent and ent[0] == mt:
            return ent[1]
        val = loader(path)
        _cache[key] = (mt, val)
        return val


def _max_mtime(paths):
    return max((_mtime(p) for p in paths), default=0)


def _cached_m(key, paths, loader):
    """Like _cached but fresh-keyed on the newest mtime of several files (0-arg loader)."""
    with _lock:
        ent = _cache.get(key)
        mt = _max_mtime(paths)
        if ent and ent[0] == mt:
            return ent[1]
        val = loader()
        _cache[key] = (mt, val)
        return val


def _payload(cache_path, srcs, builder, memkey):
    """A built JSON payload, cached in memory and on disk — both invalidated by the
    mtimes of the source extracts, so a re-analysis rebuilds automatically. This is
    what makes re-opening an already-analyzed class instant: the alternative is
    re-parsing ~50 MB of CSVs (~3-5 s) on every click."""
    def load():
        mt = _max_mtime(srcs)
        if _mtime(cache_path) >= mt > 0:
            try:
                with open(cache_path) as f:
                    return json.load(f)
            except (ValueError, OSError):
                pass
        val = builder()
        if val is not None:
            try:
                tmp = cache_path + ".tmp"
                with open(tmp, "w") as f:
                    json.dump(val, f, separators=(",", ":"))
                os.replace(tmp, cache_path)
            except OSError:
                pass
        return val
    return _cached_m(memkey, srcs + [cache_path], load)


def invalidate(data_dir=None):
    with _lock:
        if data_dir is None:
            _cache.clear()
        else:
            for k in [k for k in _cache if k[0] == data_dir]:
                del _cache[k]


def _read_csv(path):
    with open(path) as f:
        return [r for r in csv.reader(f)]


# ---------------------------------------------------------------- raw extracts

def load_hist(data_dir):
    """[(name, objects, shallow)] — complete class histogram."""
    def ld(p):
        rows = []
        for r in _read_csv(p):
            if len(r) >= 3 and r[0] != "Class Name" and r[1].isdigit():
                rows.append((r[0], int(r[1]), int(r[2])))
        return rows
    return _cached((data_dir, "hist"), os.path.join(data_dir, "histogram.csv"), ld)


def load_dom(data_dir):
    """[(name, objects, shallow, retained)] — TOP-LEVEL dominators only (MAT export
    limitation: objects dominated by another object roll up into their dominator)."""
    def ld(p):
        rows = []
        for r in _read_csv(p):
            if len(r) >= 5 and r[0] != "Class Name" and r[1].lstrip("-").isdigit():
                rows.append((r[0], int(r[1]), int(r[2]), int(r[3])))
        return rows
    return _cached((data_dir, "dom"), os.path.join(data_dir, "dominator_by_class.csv"), ld)


def load_meta(data_dir):
    return _cached((data_dir, "meta"), os.path.join(data_dir, "meta.json"),
                   lambda p: json.load(open(p)) if os.path.exists(p) else {})


# ---------------------------------------------------------------- helpers

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


def analysis_index(data_dir):
    """full class name -> {key, comp: bool, anat: [sample counts available]}"""
    def build(meta_p):
        meta = load_meta(data_dir)
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
    return _cached((data_dir, "anaidx"), os.path.join(data_dir, "meta.json"), build)


def rs_totals(data_dir):
    """full class -> (retained shallow total, retained object count, #classes in set).
    The rs CSV is the retained-set histogram and includes the class itself."""
    def build(meta_p):
        meta = load_meta(data_dir)
        rs = meta.get("rs", {})
        out = {}
        for full, st in analysis_index(data_dir).items():
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
    return _cached((data_dir, "rstot"), os.path.join(data_dir, "meta.json"), build)


def stats(data_dir):
    hist = load_hist(data_dir)
    dom = load_dom(data_dir)
    meta = load_meta(data_dir)
    return {
        "totalRetained": sum(r[3] for r in dom),
        "totalShallow": sum(r[2] for r in hist),
        "totalObjects": sum(r[1] for r in hist),
        "classes": len(hist),
        "analyzed": sum(1 for st in analysis_index(data_dir).values() if st["comp"]),
        "modules": int(meta.get("modules", 3010)),
        "buildFileBytes": meta.get("buildFileBytes", 0),
        "dump": meta.get("dump", ""),
    }


# ---------------------------------------------------------------- class table

def class_table(data_dir):
    """One row per class (lambda families merged), joined with analysis status.
    Row: {disp,name,pkg,cat,c,s,pi,r,comp,anat,lams,analyzable}"""
    idx = analysis_index(data_dir)
    tots = rs_totals(data_dir)
    fams = {}
    for name, c, s in load_hist(data_dir):
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


# ---------------------------------------------------------------- treemap data

def build_tree(leaves):
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


def _merged(rows, n):
    fams = {}
    for row in rows:
        disp = norm_lambda(row[0])
        e = fams.get(disp)
        if e is None:
            e = fams[disp] = [disp] + [0] * n
        for i in range(n):
            e[1 + i] += row[1 + i]
    return list(fams.values())


def trees(data_dir):
    """Dominator (top-level only) + histogram trees for the treemap, lambda-merged."""
    dom_leaves = []
    for c, o, s, r in _merged(load_dom(data_dir), 3):
        pkg, name = split_pkg(c)
        dom_leaves.append({"name": name, "disp": c, "pkg": pkg, "cat": cat_of(c),
                           "c": o, "s": s, "r": r, "leaf": 1})
    hist_leaves, other = [], {}
    for c, o, s in _merged(load_hist(data_dir), 2):
        if s >= HIST_MIN_SHALLOW:
            pkg, name = split_pkg(c)
            hist_leaves.append({"name": name, "disp": c, "pkg": pkg, "cat": cat_of(c),
                                "c": o, "s": s, "leaf": 1})
        else:
            k = (cat_of(c), split_pkg(c)[0])
            acc = other.setdefault(k, [0, 0, 0])
            acc[0] += o
            acc[1] += s
            acc[2] += 1
    for (cat, pkg), (cnt, shallow, n) in other.items():
        hist_leaves.append({"name": f"· other ({n} classes)", "disp": f"{pkg} — {n} smaller classes",
                            "pkg": pkg, "cat": cat, "c": cnt, "s": shallow, "leaf": 1})
    return {"dom": build_tree(dom_leaves), "hist": build_tree(hist_leaves)}


# ---------------------------------------------------------------- composition

def composition(data_dir, full):
    """Retained-set composition ("what's inside") for an analyzed class."""
    st = analysis_index(data_dir).get(full)
    if not st or not st["comp"]:
        return None
    meta = load_meta(data_dir)
    p = os.path.join(data_dir, meta.get("rs", {}).get(st["key"], f"rs_{st['key']}.csv"))
    if not os.path.exists(p):
        return None
    rows = []
    for r in _read_csv(p)[1:]:
        if len(r) >= 3 and r[1].isdigit():
            rows.append((r[0], int(r[1]), int(r[2])))
    rows.sort(key=lambda r: -r[2])
    return {"rows": [[c, s, n] for c, n, s in rows[:100]],
            "totalShallow": sum(s for _, _, s in rows),
            "totalObjects": sum(n for _, n, _ in rows),
            "classes": len(rows)}


# ---------------------------------------------------------------- anatomy

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
    mtime freshness checks, so a cached payload can be served WITHOUT re-parsing the
    ~50 MB of CSVs first. None when the extraction does not exist."""
    anatdir = os.path.join(data_dir, "anat")
    prefix = f"{key}_s{K}_" if os.path.exists(os.path.join(anatdir, f"{key}_s{K}_nodes.csv")) else f"{key}_"
    if not os.path.exists(os.path.join(anatdir, f"{prefix}nodes.csv")):
        return None
    paths = [os.path.join(anatdir, f"{prefix}{k}.csv") for k in ("nodes", "fields", "edges", "edgesfull", "strings")]
    return [*paths, os.path.join(anatdir, f"{key}_s{K}.json"), os.path.join(data_dir, "meta.json")]


def _anat_src(data_dir, key, K):
    """Parsed anatomy extraction (nodes/fields/edges/strings), cached on source mtimes.
    Shared by anatomy() and anat2() — the parse only runs on a payload-cache miss."""
    srcs = _anat_srcs(data_dir, key, K)
    if not srcs:
        return None
    return _cached_m((data_dir, "anatsrc", key, K), srcs,
                     lambda: _anat_src_load(data_dir, key, K, srcs))


def _anat_src_load(data_dir, key, K, srcs):
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
        ids = json.load(open(side)).get("ids")
    if ids is None:
        ids = load_meta(data_dir).get("ids", {}).get(key, [])
    return {"nodes": nodes, "addr2id": addr2id, "refs": refs, "prims": prims,
            "edges": edges, "edgesFull": edges_full, "elen": elen, "hasFullEdges": has_full,
            "strings": strings, "ids": [int(x) for x in ids]}


def _split_refs(src):
    """v1-style views of the parsed fields: non-skipped refs split by in-set target."""
    named, allrefs = {}, {}
    for oid, lst in src["refs"].items():
        for name, tgt, sk in lst:
            if sk:
                continue
            allrefs.setdefault(oid, []).append((name, tgt))
            if tgt in src["addr2id"]:
                named.setdefault(oid, []).append((name, src["addr2id"][tgt]))
    prims = {}
    for oid, lst in src["prims"].items():
        ps = [(n, v) for n, v, sk in lst if not sk]
        if ps:
            prims[oid] = ps
    return named, allrefs, prims


def _anatomy_build(src, full, K, avail, max_depth, max_kids):
    nodes, addr2id = src["nodes"], src["addr2id"]
    named, allrefs, prims = _split_refs(src)
    edges, strings = src["edges"], src["strings"]

    def new_node(label, cls):
        return {"name": label, "full": cls, "n": 0, "s": 0, "r": 0, "kids": {}}

    root = new_node(_short(full), full)
    visited = set()
    stack = []
    pres = {}   # depth-1 label -> set of root ids where the field is non-null
    shared = new_node("(shared — held by others too)", "(external)")
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
                child = shared["kids"].setdefault(label, new_node(label, "(external)"))
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
                child = agg["kids"].setdefault(label, new_node(label, "(field)"))
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
            child = agg["kids"].setdefault(clabel, new_node(clabel, tcls))
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
        un = new_node("(held via untracked/shared refs)", "")
        for oid in leftover:
            un["n"] += 1
            un["s"] += nodes[oid]["used"]
            un["r"] += nodes[oid]["ret"]
            ccls = nodes[oid]["cls"]
            child = un["kids"].setdefault(ccls, new_node(_short(ccls), ccls))
            child["n"] += 1
            child["s"] += nodes[oid]["used"]
            child["r"] += nodes[oid]["ret"]
        root["kids"]["(held via untracked/shared refs)"] = un

    def attach_pres(n):
        if n["name"] in pres:
            n["pres"] = len(pres[n["name"]])
        for k in n["kids"].values():
            attach_pres(k)

    attach_pres(root)

    def finish(n):
        kids = sorted(n["kids"].values(), key=lambda k: (-k["r"], -k["s"]))
        if len(kids) > max_kids:
            rest = kids[max_kids:]
            more = {"name": f"· {len(rest)} more", "full": "",
                    "n": sum(k["n"] for k in rest), "s": sum(k["s"] for k in rest),
                    "r": sum(k["r"] for k in rest), "kids": {}}
            kids = kids[:max_kids] + [more]
        return {"name": n["name"], "full": n["full"], "n": n["n"], "s": n["s"],
                "r": n["r"], **({"pres": n["pres"]} if "pres" in n else {}),
                "kids": [finish(k) for k in kids]}

    return {"tree": finish(root), "samples": K, "available": avail, "roots": root["n"]}


def anatomy(data_dir, full, samples=None, max_depth=14, max_kids=40):
    """Aggregated named-reference tree for one class, from the union retained set of
    K evenly-spread sample instances. Depth-1 children carry `pres` = in how many of
    the K samples the field was non-null (fixes the 'missing resolutionStrategy' trap:
    a field present in 5/32 samples is real, just not in every instance)."""
    st = analysis_index(data_dir).get(full)
    if not st or not st["anat"]:
        return None
    key = st["key"]
    avail = st["anat"]
    K = samples if samples in avail else avail[-1]
    srcs = _anat_srcs(data_dir, key, K)
    if not srcs:
        return None
    cache_p = os.path.join(data_dir, "anat", f"{key}_s{K}_view1.json")
    return _payload(cache_p, srcs,
                    lambda: _anatomy_build(_anat_src(data_dir, key, K), full, K, avail, max_depth, max_kids),
                    (data_dir, "v1", key, K))


# ---------------------------------------------------------------- anatomy v2 (full graph)

def _new_agg(label, cls, sk=False):
    n = {"name": label, "full": cls, "n": 0, "s": 0, "r": 0, "refs": 0, "kids": {}}
    if sk:
        n["sk"] = 1
    return n


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


def _finish_agg(n, max_kids):
    kids = sorted(n["kids"].values(), key=lambda k: (-k["r"], -k["s"]))
    if len(kids) > max_kids:
        rest = kids[max_kids:]
        more = {"name": f"· {len(rest)} more", "full": "",
                "n": sum(k["n"] for k in rest), "s": sum(k["s"] for k in rest),
                "r": sum(k["r"] for k in rest), "refs": sum(k["refs"] for k in rest), "kids": {}}
        kids = kids[:max_kids] + [more]
    out = {"name": n["name"], "full": n["full"], "n": n["n"], "s": n["s"], "r": n["r"]}
    if "pres" in n:
        out["pres"] = n["pres"]
    if n.get("sk"):
        out["sk"] = 1
    if n["refs"] > n["n"]:
        out["refs"] = n["refs"]
    out["kids"] = [_finish_agg(k, max_kids) for k in kids]
    return out


def _anat2_build(src, full, K, avail, max_depth, max_kids):
    nodes, addr2id = src["nodes"], src["addr2id"]
    edges, edges_full, elen = src["edges"], src["edgesFull"], src["elen"]
    strings, ids = src["strings"], src["ids"]
    named, allrefs, prims = {}, {}, {}
    for oid, lst in src["refs"].items():
        for name, tgt, sk in lst:
            if not sk:
                allrefs.setdefault(oid, []).append((name, tgt))
            if tgt in addr2id:
                named.setdefault(oid, []).append((name, addr2id[tgt], sk))
    for oid, lst in src["prims"].items():
        ps = [(n, v) for n, v, sk in lst if not sk]
        if ps:
            prims[oid] = ps

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
                              "tree": _finish_agg(un, max_kids)})
        untracked.sort(key=lambda g: -g["r"])

    def attach_pres(n):
        if n["name"] in pres:
            n["pres"] = len(pres[n["name"]])
        for k in n["kids"].values():
            attach_pres(k)

    attach_pres(root)

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

    return {"tree": _finish_agg(root, max_kids), "samples": K, "available": avail,
            "roots": root["n"], "untracked": untracked, "fullEdges": src["hasFullEdges"],
            "depth": max_depth, "graph": {"nodes": gnodes, "links": links}}


ANAT2_CACHE_VER = 2   # bump when the anat2 payload shape changes (disk caches rebuild)


def anat2(data_dir, full, samples=None, max_depth=32, max_kids=40):
    """Full-graph reference tree: complete outbounds (edgesfull extraction when
    present), a much deeper walk, strings and skipped fields (this$0, …) traversed.
    Aggregate nodes carry `refs` (in-set inbound reference count; refs > n = shared
    within the set). Whatever stays unreachable keeps its tree structure in
    `untracked`, grouped by cause. Also emits a class-level reference `graph`."""
    st = analysis_index(data_dir).get(full)
    if not st or not st["anat"]:
        return None
    key = st["key"]
    avail = st["anat"]
    K = samples if samples in avail else avail[-1]
    srcs = _anat_srcs(data_dir, key, K)
    if not srcs:
        return None
    cache_p = os.path.join(data_dir, "anat", f"{key}_s{K}_view2_v{ANAT2_CACHE_VER}.json")
    return _payload(cache_p, srcs,
                    lambda: _anat2_build(_anat_src(data_dir, key, K), full, K, avail, max_depth, max_kids),
                    (data_dir, "v2", key, K))


# ---------------------------------------------------------------- compare

def _merged_hist(data_dir):
    fams = {}
    for name, c, s in load_hist(data_dir):
        d = norm_lambda(name)
        e = fams.get(d)
        if e is None:
            e = fams[d] = [0, 0]
        e[0] += c
        e[1] += s
    return fams


def _merged_dom(data_dir):
    fams = {}
    for name, o, s, r in load_dom(data_dir):
        d = norm_lambda(name)
        e = fams.get(d)
        if e is None:
            e = fams[d] = [0, 0, 0]
        e[0] += o
        e[1] += s
        e[2] += r
    return fams


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


def anatomy_diff(old_dir, new_dir, full):
    """Diff of two reference trees, matched by label path. Values are per-sampled-instance
    averages (tree totals / K), so extractions with different sample counts still compare.
    Answers 'what changed inside a typical instance'; instance-count changes are in the
    histogram deltas. String-value labels differ per run and appear as remove+add pairs."""
    a, b = anatomy(old_dir, full), anatomy(new_dir, full)
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


def compare_payload(old_dir, new_dir):
    A, B = _merged_hist(old_dir), _merged_hist(new_dir)
    rows = []
    for k in set(A) | set(B):
        co, so = A.get(k, (0, 0))
        cn, sn = B.get(k, (0, 0))
        rows.append([k, co, cn, cn - co, so, sn, sn - so])
    # top-level dominator deltas (owned memory — closer to cause than shallow histogram)
    DA, DB = _merged_dom(old_dir), _merged_dom(new_dir)
    dom = []
    for k in set(DA) | set(DB):
        oo, so, ro = DA.get(k, (0, 0, 0))
        on, sn, rn = DB.get(k, (0, 0, 0))
        dom.append([k, oo, on, so, sn, ro, rn, rn - ro])
    dom.sort(key=lambda r: -abs(r[7]))
    # retained deltas for classes analyzed in both dumps
    ta, tb = rs_totals(old_dir), rs_totals(new_dir)
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
    ia, ib = analysis_index(old_dir), analysis_index(new_dir)
    analyzed = {"old": sorted(f for f, st in ia.items() if st["comp"]),
                "new": sorted(f for f, st in ib.items() if st["comp"])}
    # anatomy diffs for classes with an extraction in both dumps
    anats = {}
    for full in sorted(set(ia) & set(ib)):
        if ia[full]["anat"] and ib[full]["anat"]:
            d = anatomy_diff(old_dir, new_dir, full)
            if d:
                anats[full] = d
    return {
        "old": stats(old_dir), "new": stats(new_dir),
        "rows": rows,
        "dom": dom,
        "waterfall": _waterfall(rows),
        "retained": retained,
        "proxies": proxies,
        "analyzed": analyzed,
        "anats": anats,
    }


# ---------------------------------------------------------------- dumps

def data_dir_of(name):
    if not re.match(r"^[\w.-]+$", name) or ".." in name:
        return None
    d = os.path.join(REPORT_ROOT, name, "data")
    if os.path.isdir(d) and os.path.exists(os.path.join(d, "histogram.csv")):
        return d
    return None


def list_dumps():
    out = []
    for d in sorted(os.listdir(REPORT_ROOT)):
        data = data_dir_of(d)
        if not data:
            continue
        try:
            st = stats(data)
        except Exception as e:   # noqa: BLE001 - interrupted bootstrap/download must not 500 the whole list
            out.append({"name": d, "incomplete": True, "error": str(e)})
            continue
        st["name"] = d
        out.append(st)
    return out
