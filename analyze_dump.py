#!/usr/bin/env python3
"""Heap-dump bootstrap: MAT headless global extracts -> CSV.

Usage:
  analyze_dump.py <path/to/heap.hprof> [name] [--jobs=N]

Bootstraps dumps/<name>/data/ with the histogram + dominator extracts. The heavy
part — parsing the hprof into MAT index files — happens automatically on the first
query when indexes are missing; with pre-built indexes (downloaded from an idx-<tag>
release by serve.py's Remote tab) the bootstrap takes about a minute.
Independent MAT queries run in parallel, up to --jobs concurrent MAT JVMs (default 2,
env MAT_JOBS) — each JVM can grow to the -Xmx in MemoryAnalyzer.ini (10g), so raise
jobs only if RAM allows. Everything is resumable: already-existing CSVs are skipped.
Per-class analysis (also used by serve.py for on-demand queries): analyze_class().

After bootstrapping, start the interactive UI with:  python3 serve.py
A static snapshot can be exported with:              python3 generate.py --data <data_dir> --out <html>
"""
import csv, hashlib, json, os, re, shutil, subprocess, sys, threading, zipfile, glob

HERE = os.path.dirname(os.path.abspath(__file__))   # repo root
ROOT = HERE
REPORT_ROOT = os.path.join(HERE, "dumps")
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "tools"))
import matindex
import get_mat
MAT = os.environ.get("MAT_PARSE", get_mat.parse_sh())
WS = "/tmp/mat-headless-ws"
MAX_EDGE = 48
EDGE_FULL_CAP = 1024   # supplementary complete-outbounds extraction for objects with >MAX_EDGE refs
MAX_STRINGS = 400
SAMPLES = 32
IDS_LIMIT = 1_000_000
JOBS = int(os.environ.get("MAT_JOBS", "2"))   # concurrent MAT JVMs — each can grow to MAT's -Xmx (10g)
_mat_sem = threading.Semaphore(JOBS)          # bounds how many MAT processes run at once
_meta_lock = threading.Lock()                 # serializes load_meta/save_meta across worker threads


def set_jobs(n):
    global JOBS, _mat_sem
    JOBS = max(1, n)
    _mat_sem = threading.Semaphore(JOBS)


def par(fns):
    """Run zero-arg callables on plain threads; results in submission order. Threads are
    cheap here (they just wait on subprocesses) — MAT JVM concurrency stays capped by _mat_sem.
    A failing callable logs its traceback and yields None (callers already handle None)."""
    out = [None] * len(fns)

    def run(i, f):
        try:
            out[i] = f()
        except Exception:
            import traceback
            print(f"par(): task {i} failed:\n{traceback.format_exc()[-1200:]}", flush=True)

    ts = [threading.Thread(target=run, args=(i, f)) for i, f in enumerate(fns)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    return out

def log(msg):
    print(msg, flush=True)


def suffix(tag, key):
    """MAT truncates -filename_suffix at 20 chars, so long keys need hashing."""
    s = f"{tag}_{key}"
    if len(s) > 20:
        s = f"{tag}_{key[:6]}_{hashlib.sha1(key.encode()).hexdigest()[:5]}"
    return s[:20]


def ensure_mat(log=log):
    """MAT must exist before any query runs; download it on first use."""
    global MAT
    if os.path.exists(MAT):
        return MAT
    MAT = get_mat.ensure(log=log)
    return MAT


def run_mat(hprof, outdir, suffix, command, keep_name, limit=2000000, log=log):
    """Run one MAT headless query; move resulting CSV to outdir/keep_name. Resumable."""
    dst = os.path.join(outdir, keep_name)
    if os.path.exists(dst):
        return dst
    ensure_mat(log)
    # unique workspace per query: concurrent MAT instances can't share one (Eclipse .lock),
    # and separate analyze_dump.py runs previously collided on the single shared WS
    ws = f"{WS}-{os.getpid()}-{suffix}"
    cmd = [MAT, "-data", ws, hprof, f"-command={command}", "-format=csv",
           f"-limit={limit}", f"-filename_suffix={suffix}", "org.eclipse.mat.api:query"]
    log(f"  MAT {keep_name} ...")
    with _mat_sem:
        # a compacted dump (indexes stored as *.index.zst) is restored on demand;
        # no-op fast path when nothing is compacted (see matindex.py)
        matindex.restore(os.path.dirname(os.path.abspath(hprof)), log=log)
        r = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, timeout=7200)
    shutil.rmtree(ws, ignore_errors=True)
    base = os.path.splitext(os.path.basename(hprof))[0]
    # MAT writes the report zip next to the hprof, not into cwd.
    z = os.path.join(os.path.dirname(hprof), f"{base}_{suffix}.zip")
    if not os.path.exists(z):
        log(f"FAILED {suffix}\n{r.stdout[-700:]}\n{r.stderr[-700:]}")
        return None
    tmp = f"/tmp/qout/{os.getpid()}-{suffix}"
    shutil.rmtree(tmp, ignore_errors=True); os.makedirs(tmp, exist_ok=True)
    with zipfile.ZipFile(z) as zf:
        zf.extractall(tmp)
    csvs = sorted(glob.glob(os.path.join(tmp, "pages", "*.csv")))
    os.remove(z)
    if not csvs:
        log(f"no CSV in {suffix}")
        return None
    if len(csvs) > 1:
        log(f"WARNING: {suffix} produced {len(csvs)} CSV pages; keeping {os.path.basename(csvs[0])}"
            " — result may be truncated")
    shutil.move(csvs[0], dst)
    return dst


def read_rows(path):
    with open(path) as f:
        return [r for r in csv.reader(f)]


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


def load_meta(data):
    p = os.path.join(data, "meta.json")
    return json.load(open(p)) if os.path.exists(p) else {}


def save_meta(data, meta):
    with open(os.path.join(data, "meta.json"), "w") as f:
        json.dump(meta, f, indent=1)


def analyze_class(hprof, data, key, cls, samples=SAMPLES, anatomy=True, log=log):
    """Retained-set composition + (optionally) reference-tree anatomy for one class.

    Resumable: existing CSVs are reused, so escalating to more samples only runs the
    missing extractions. Anatomy files are versioned by sample count ({key}_s{K}_*).
    Independent queries run in parallel (MAT JVM count capped by JOBS).
    Updates meta.json. Returns a summary dict (with "error" on failure)."""
    anat = os.path.join(data, "anat")
    os.makedirs(anat, exist_ok=True)
    out = {"key": key, "cls": cls}
    # retained-set composition and the full id list are independent — overlap them
    tasks = [lambda: run_mat(hprof, data, suffix("rs", key), f"show_retained_set {cls}",
                             f"rs_{key}.csv", log=log)]
    if anatomy:
        tasks.append(lambda: run_mat(hprof, data, suffix("idsall", key),
                                     f'oql "SELECT s.@objectId FROM INSTANCEOF {cls} s"',
                                     f"idsall_{key}.csv", limit=IDS_LIMIT, log=log))
    res = par(tasks)
    if res[0] is None:
        out["error"] = "retained-set query failed"
        return out
    out["rs"] = f"rs_{key}.csv"
    with _meta_lock:
        meta = load_meta(data)
        meta.setdefault("classes", {})[key] = cls
        meta.setdefault("rs", {})[key] = f"rs_{key}.csv"
        save_meta(data, meta)
    if not anatomy:
        return out
    # full id list -> evenly-spaced sample of K instances
    p = res[1]
    ids = []
    if p:
        ids = [r[0] for r in read_rows(p)[1:] if r and r[0].isdigit()]
    picked = sample_even(ids, samples)
    if not picked:
        out["error"] = "no instance ids"
        return out
    K = len(picked)
    out["samples"] = K
    # per-extraction sidecar so the anatomy can be rebuilt with the same ids
    with open(os.path.join(anat, f"{key}_s{K}.json"), "w") as f:
        json.dump({"key": key, "cls": cls, "samples": K, "ids": picked}, f)
    idx = ", ".join(f"outbounds(o)[{i}]" for i in range(MAX_EDGE))
    idx_full = ", ".join(f"outbounds(o)[{i}]" for i in range(EDGE_FULL_CAP))
    sub = subselect(cls, picked)
    # nodes / edges / fields scan the same retained set independently — run concurrently.
    # edgesfull captures complete outbounds for objects with >MAX_EDGE refs (the plain
    # edges query truncates at MAX_EDGE, which silently orphans big-array children —
    # the "(held via untracked/shared refs)" bucket).
    par([
        lambda: run_mat(hprof, anat, suffix("n2", f"{key}_s{K}"),
                f'oql "SELECT o.@objectId, toHex(o.@objectAddress), classof(o).@name, o.@usedHeapSize, o.@retainedHeapSize FROM OBJECTS ({sub}) o"',
                f"{key}_s{K}_nodes.csv", log=log),
        lambda: run_mat(hprof, anat, suffix("e2", f"{key}_s{K}"),
                f'oql "SELECT o.@objectId, outbounds(o).length, {idx} FROM OBJECTS ({sub}) o"',
                f"{key}_s{K}_edges.csv", log=log),
        lambda: run_mat(hprof, anat, suffix("f2", f"{key}_s{K}"),
                f'oql "SELECT o.@objectId, o.getFields() FROM OBJECTS ({sub}) o WHERE o implements org.eclipse.mat.snapshot.model.IInstance"',
                f"{key}_s{K}_fields.csv", log=log),
        lambda: run_mat(hprof, anat, suffix("ef", f"{key}_s{K}"),
                f'oql "SELECT o.@objectId, outbounds(o).length, {idx_full} FROM OBJECTS ({sub}) o WHERE outbounds(o).length > {MAX_EDGE}"',
                f"{key}_s{K}_edgesfull.csv", log=log),
    ])
    nodes_p = os.path.join(anat, f"{key}_s{K}_nodes.csv")
    strings_dst = os.path.join(anat, f"{key}_s{K}_strings.csv")
    if os.path.exists(nodes_p) and not os.path.exists(strings_dst):
        addrs = [r[1] for r in read_rows(nodes_p)[1:]
                 if len(r) >= 5 and r[2] == "java.lang.String"][:MAX_STRINGS]
        if addrs:
            run_mat(hprof, anat, suffix("s2", f"{key}_s{K}"),
                    f'oql "SELECT toHex(o.@objectAddress), toString(o) FROM OBJECTS {",".join(addrs)} o"',
                    f"{key}_s{K}_strings.csv", log=log)
    with _meta_lock:
        ks = set(load_meta(data).get("anatSamples", {}).get(key, []))
        ks.add(K)
        meta = load_meta(data)
        meta.setdefault("anatSamples", {})[key] = sorted(ks)
        meta.setdefault("ids", {})[key] = picked
        save_meta(data, meta)
    return out


def bootstrap(hprof, name, log=log):
    """Global extracts for one dump -> dumps/<name>/data/. Resumable.

    The histogram runs first and alone: when the MAT indexes are missing (no idx
    release downloaded) the first query triggers the full hprof parse, and concurrent
    parsers would clobber each other's index files. With pre-built indexes in place
    the whole bootstrap takes about a minute."""
    outdir = os.path.join(REPORT_ROOT, name)
    data = os.path.join(outdir, "data")
    os.makedirs(data, exist_ok=True)
    log(f"analyzing {hprof} -> {outdir} (jobs={JOBS})")

    if run_mat(hprof, data, "histogram", "histogram", "histogram.csv", log=log) is None:
        raise RuntimeError("histogram failed")
    log("  histogram done (parse complete)")

    # dominator groupings are independent — run in parallel (MAT JVMs capped by JOBS)
    par([
        lambda: run_mat(hprof, data, "domclass", "dominator_tree -groupBy BY_CLASS",
                        "dominator_by_class.csv", log=log),
        lambda: run_mat(hprof, data, "dompkg", "dominator_tree -groupBy BY_PACKAGE",
                        "dominator_by_package.csv", log=log),
    ])

    # module count = DefaultScriptHandler instances (fallback 3010)
    modules = 3010
    domp = os.path.join(data, "dominator_by_class.csv")
    if os.path.exists(domp):
        for r in read_rows(domp)[1:]:
            if len(r) >= 4 and r[0].endswith("DefaultScriptHandler_Decorated") and r[1].isdigit():
                modules = int(r[1])

    meta = load_meta(data)   # merge — don't clobber prior on-demand analyses
    meta["modules"] = modules
    meta["dump"] = os.path.basename(hprof)
    save_meta(data, meta)

    log(f"data: {data}")
    return data


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    hprof = os.path.abspath(sys.argv[1])
    positional = [a for a in sys.argv[2:] if not a.startswith("--")]
    for a in sys.argv[2:]:
        if a.startswith("--jobs="):
            set_jobs(int(a.split("=", 1)[1]))
    name = positional[0] if positional else os.path.splitext(os.path.basename(hprof))[0]
    data = bootstrap(hprof, name)
    outdir = os.path.dirname(data.rstrip("/"))
    log(f"interactive UI: python3 {os.path.join(HERE, 'serve.py')}")
    log(f"static snapshot: python3 {os.path.join(HERE, 'generate.py')} --data {data} --out {os.path.join(outdir, 'index.html')}")
    log(f"compact MAT indexes (~60% less disk): python3 {os.path.join(HERE, 'compact.py')} {outdir}")


if __name__ == "__main__":
    main()
