"""backend/mat/extract — the MAT subprocess gateway.

Everything that spawns a process lives here: MatRunner (resolve/download MAT,
restore compacted indexes, run one headless query -> CSV) plus the extraction
helpers that define the query shapes (suffix, subselect, sample_even, _par).

Concurrency: the job registry's serial MAT queue bounds jobs; within one job
_par() overlaps independent MAT JVMs up to MAT_JOBS (each can grow to
-Xmx10g). A failed extraction raises — it is never recorded as a success.
"""
from __future__ import annotations

import fcntl
import glob
import hashlib
import importlib.util
import os
import shutil
import subprocess
import threading
import zipfile
from collections import deque
from concurrent.futures import ThreadPoolExecutor

_HERE = os.path.dirname(os.path.abspath(__file__))          # backend/mat/
_REPO = os.path.dirname(os.path.dirname(_HERE))             # repo root
_GET_MAT = os.path.join(_REPO, "tools", "get_mat.py")

# MAT invocation
WS = "/tmp/mat-headless-ws"
MAT_TIMEOUT = 7200            # subprocess timeout for one MAT query
MAX_EDGE = 48
EDGE_FULL_CAP = 1024          # supplementary complete-outbounds extraction for objects with >MAX_EDGE refs
MAX_STRINGS = 400
SAMPLES = 32
IDS_LIMIT = 1_000_000
MAT_JOBS = int(os.environ.get("MAT_JOBS", "2"))   # concurrent MAT JVMs within one job (each can grow to -Xmx10g)
ZSTD = os.environ.get("ZSTD", "zstd")

_get_mat_mod = None


def _get_mat():
    """tools/get_mat.py, loaded by path: it lives outside the backend package
    because the CI workflow also runs it as a standalone script."""
    global _get_mat_mod
    if _get_mat_mod is None:
        spec = importlib.util.spec_from_file_location("get_mat", _GET_MAT)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        _get_mat_mod = mod
    return _get_mat_mod


# ---------------------------------------------------------------- query-shape helpers

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
    The first failure raises — a failed extraction must never be recorded as
    a success."""
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


# ---------------------------------------------------------------- the runner

class MatRunner:
    """Owns every MAT subprocess. Thread-safe; callers provide the Job handle
    whose log receives streamed MAT output (capped, via the job registry)."""

    def __init__(self, jobs):
        self._jobs = jobs
        self._mat = None            # resolved ParseHeapDump.sh path
        self._mat_lock = threading.Lock()

    def ensure_mat(self, log):
        """MAT must exist before any query runs; download it on first use."""
        with self._mat_lock:
            if self._mat and os.path.exists(self._mat):
                return self._mat
            get_mat = _get_mat()
            p = os.environ.get("MAT_PARSE", get_mat.parse_sh())
            if not os.path.exists(p):
                p = get_mat.ensure(log=log)
            self._mat = p
            return p

    def restore_indexes(self, dump_dir, log):
        """zstd -d every *.index.zst whose raw file is missing — MAT must never
        run against a compacted dump unrestored. No-op fast path when nothing
        is missing."""
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

    def run(self, job, hprof, outdir, sfx, command, keep_name, limit=2000000):
        """Run one MAT headless query; move the resulting CSV to outdir/keep_name.
        Resumable (existing dst short-circuits). Raises RuntimeError with the MAT
        output tail on any failure — never returns None for a failed extraction."""
        dst = os.path.join(outdir, keep_name)
        if os.path.exists(dst):
            return dst
        log = lambda m: self._jobs.log(job, m)
        mat = self.ensure_mat(log)
        # a compacted dump (indexes stored as *.index.zst) is restored on demand
        self.restore_indexes(os.path.dirname(os.path.abspath(hprof)), log)
        # unique workspace per query: concurrent MAT instances can't share one
        # (Eclipse .lock); a shared workspace would collide across runs
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
                    self._jobs.log(job, line)

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
            shutil.rmtree(ws, ignore_errors=True)   # always, success or failure
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
