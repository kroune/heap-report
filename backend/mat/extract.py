"""backend/mat/extract — the MAT subprocess gateway.

Everything that spawns a process lives here: MatRunner (resolve/download MAT,
restore compacted indexes, run one headless query -> CSV) plus the extraction
helpers that define the query shapes (suffix, subselect, sample_even, _par).

Concurrency: the job registry's serial MAT queue bounds jobs; within one job
_par() overlaps independent MAT JVMs up to MAT_JOBS (each can grow to
-Xmx10g). A failed extraction raises — it is never recorded as a success.
Cross-process, .matindex.lock serializes file mutation (compact/restore take
it exclusive) against MAT reads (a run holds it SHARED for its whole
lifetime) — without that, a compact racing a MAT run deletes the raw indexes
mid-read, MAT falls back to parsing, and two parsing JVMs collide on
daemon.lock.index ("Concurrent parsing error").

Freshness: MAT reparses the whole dump when the .hprof's mtime is newer than
daemon.index's — a pure mtime check. A (re-)assembled download always stamps
the hprof with "now" while pre-built indexes keep their CI build time, so a
freshly downloaded dump would reparse on its first query (40 min, and fatal
under _par). _pin_hprof() therefore keeps the hprof mtime <= the index set's
and clears stale parse debris (a crashed parse leaves daemon.lock.index,
which fails every later parse with "Concurrent parsing error").
"""
from __future__ import annotations

import fcntl
import glob
import hashlib
import importlib.util
import os
import re
import shutil
import subprocess
import threading
import time
import zipfile
from collections import deque
from concurrent.futures import ThreadPoolExecutor

from .. import core

_HERE = os.path.dirname(os.path.abspath(__file__))          # backend/mat/
_REPO = os.path.dirname(os.path.dirname(_HERE))             # repo root
_GET_MAT = os.path.join(_REPO, "tools", "get_mat.py")

# MAT invocation
WS = "/tmp/mat-headless-ws"
MAT_TIMEOUT = 7200            # subprocess timeout for one MAT query
MAX_EDGE = 48
EDGE_FULL_CAP = 1024          # supplementary complete-outbounds extraction for objects with >MAX_EDGE refs
MAX_STRINGS = 400
SAMPLES = 8
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


def _report_text(d):
    """Stripped text of an extracted MAT report dir — where MAT states why a
    query yielded no CSV page: 'did not yield any result' for an empty result
    (OQLTextResult has no csv outputter), 'Problem reported: ...' for a real
    query error. Both arrive with rc=0, so this text is the only way to tell
    a legitimately empty extraction from a failure."""
    out = []
    pages = sorted(glob.glob(os.path.join(d, "pages", "*.html")))
    for p in [os.path.join(d, "index.html")] + pages:
        if os.path.exists(p):
            with open(p, errors="replace") as f:
                out.append(re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", f.read())))
    return " ".join(out)


def _decompress_one(zst):
    """zstd -d one compacted index; restored raw mtime := zst mtime (the convention
    that lets re-compact drop untouched raws for free). A corrupt archive
    (truncated download, disk rot) is DELETED — it can never become valid, and
    keeping it would break every later restore identically — and reported as
    CorruptIndexError: the set is then incomplete and must be re-acquired."""
    raw = zst[:-4]
    tmp = f"{raw}.tmp{os.getpid()}"
    try:
        r = subprocess.run(["nice", "-n", "10", ZSTD, "-d", "-T4", "-q", "-f",
                            "-o", tmp, "--", zst], capture_output=True, text=True)
        if r.returncode != 0:
            os.remove(zst)
            raise CorruptIndexError(
                f"corrupt compacted index deleted: {zst}\n"
                f"zstd -d said: {r.stderr[-500:].strip()}\n"
                "the index set is incomplete now — it must be re-fetched "
                "(or rebuilt locally) before MAT can run")
        os.replace(tmp, raw)
        ns = os.stat(zst).st_mtime_ns
        os.utime(raw, ns=(ns, ns))
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)


class CorruptIndexError(RuntimeError):
    """A compacted index (*.index.zst) failed decompression. The file has
    already been deleted; the remaining set is partial and MAT must never run
    against it (a missing root index makes MAT reparse the whole dump). The
    caller (engine.analyze) re-acquires the set — remote re-download or local
    parse — instead of proceeding."""


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
        is missing. A corrupt archive is deleted and reported as
        CorruptIndexError: the partial set must be re-acquired before MAT runs."""
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
            for stale in glob.glob(os.path.join(dump_dir, "*.index.tmp*")):
                os.remove(stale)   # debris of a killed restore — provably dead under the EX lock
            total = sum(os.path.getsize(z) for z in todo)
            log(f"  restoring {len(todo)} compacted MAT index files "
                f"({total / 1e9:.1f} GB compressed) ...")
            with ThreadPoolExecutor(max_workers=min(4, len(todo))) as ex:
                list(ex.map(_decompress_one, todo))   # raises the first error
        finally:
            f.close()

    def _pin_hprof(self, hprof, dump_dir, log):
        """Keep the hprof mtime <= the oldest index mtime and clear stale
        parse debris. MAT's staleness check is mtime-only: a re-assembled
        hprof (download stamps it 'now') looks newer than the pre-built
        indexes (CI build time) and MAT would reparse the whole dump — slow
        alone, fatal when _par runs two JVMs (they collide on
        daemon.lock.index). The index set is already validated against the
        release manifest, so pinning is safe. Both mutations take
        .matindex.lock EXCLUSIVE: no MAT run (a shared holder) is alive then,
        so any daemon.lock.index / daemon.temp.* left behind is provably
        stale. Fast path: nothing to do -> no lock, no serialization."""
        base = os.path.splitext(os.path.basename(hprof))[0]

        def survey():
            raws = glob.glob(os.path.join(dump_dir, "*.index"))
            oldest = min((os.stat(p).st_mtime_ns for p in raws), default=None)
            debris = (glob.glob(os.path.join(dump_dir, f"{base}.lock.index*"))
                      + glob.glob(os.path.join(dump_dir, f"{base}.temp.*")))
            debris = [p for p in debris if os.path.exists(p)]
            skewed = oldest is not None and os.stat(hprof).st_mtime_ns > oldest
            return oldest, debris, skewed

        oldest, debris, skewed = survey()
        if not skewed and not debris:
            return
        f = open(os.path.join(dump_dir, ".matindex.lock"), "a")
        fcntl.flock(f, fcntl.LOCK_EX)
        try:
            oldest, debris, skewed = survey()   # recheck under the lock
            if skewed:
                os.utime(hprof, ns=(oldest, oldest))
                log(f"  pinned {os.path.basename(hprof)} mtime to the index set "
                    "(it looked newer -> MAT would have reparsed the whole dump)")
            for p in debris:
                os.remove(p)
                log(f"  removed stale parse debris: {os.path.basename(p)}")
        finally:
            f.close()

    def run(self, job, hprof, outdir, sfx, command, keep_name, limit=2000000,
            abort=None):
        """Run one MAT headless query; move the resulting CSV to outdir/keep_name.
        Resumable (existing dst short-circuits). Returns None when the query
        legitimately yielded an EMPTY result — MAT reports those as a text page
        (OQLTextResult) with no CSV outputter, so no CSV exists to move; the
        consumers in parsing.py treat the missing file as "no data". Raises
        RuntimeError with the report text and the MAT output tail on any real
        failure — a failed extraction is never recorded as a success.
        `abort` (threading.Event, the dump's cooperative-cancel flag) kills
        the JVM and raises core.Aborted — Python threads can't be cancelled,
        so the flag is polled while the subprocess runs."""
        dst = os.path.join(outdir, keep_name)
        if os.path.exists(dst):
            return dst
        log = lambda m: self._jobs.log(job, m)
        mat = self.ensure_mat(log)
        dump_dir = os.path.dirname(os.path.abspath(hprof))
        # Restore the compacted indexes (*.index.zst -> raw), then hold the index
        # lock SHARED for the whole run: compact/restore (LOCK_EX, any process)
        # must wait for MAT, not delete the indexes underneath it. A compact can
        # slip between restore and the lock, so the raws are re-checked under
        # the lock and the restore retried.
        run_lock = None
        for _ in range(3):
            self.restore_indexes(dump_dir, log)
            # BEFORE the shared run lock: the pin takes .matindex.lock
            # EXCLUSIVE, which can never be granted while this same process
            # holds it SHARED (flock is process-agnostic) — self-deadlock.
            self._pin_hprof(hprof, dump_dir, log)
            run_lock = open(os.path.join(dump_dir, ".matindex.lock"), "a")
            fcntl.flock(run_lock, fcntl.LOCK_SH)
            if all(os.path.exists(z[:-4])
                   for z in glob.glob(os.path.join(dump_dir, "*.index.zst"))):
                break
            run_lock.close()
        else:
            raise RuntimeError(f"MAT indexes in {dump_dir} keep vanishing under a "
                               "concurrent compact — giving up")
        # unique workspace per query: concurrent MAT instances can't share one
        # (Eclipse .lock); a shared workspace would collide across runs
        ws = f"{WS}-{os.getpid()}-{sfx}"
        # -vmargs locale pin LAST (everything after -vmargs goes to the JVM):
        # MAT's CSVOutputter switches the field separator to ';' when the
        # default locale's decimal separator is ',' (e.g. ru) — the CSV
        # parsers only understand ','.
        cmd = [mat, "-data", ws, hprof, f"-command={command}", "-format=csv",
               f"-limit={limit}", f"-filename_suffix={sfx}", "org.eclipse.mat.api:query",
               "-vmargs", "-Duser.language=en", "-Duser.country=US"]
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
            deadline = time.monotonic() + MAT_TIMEOUT
            while True:
                if abort is not None and abort.is_set():
                    proc.kill()
                    proc.wait()
                    raise core.Aborted(f"MAT query {sfx} aborted")
                try:
                    proc.wait(timeout=2)
                    break
                except subprocess.TimeoutExpired:
                    if time.monotonic() > deadline:
                        proc.kill()
                        proc.wait()
                        raise RuntimeError(f"MAT query {sfx} timed out after {MAT_TIMEOUT}s")
            t.join()
        finally:
            run_lock.close()
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
            # No CSV page: read WHY from the report text before deleting it —
            # an empty result is not a failure, anything else is (OQL error,
            # unsupported command, ...), and the text is the only difference.
            msg = _report_text(tmp)
            shutil.rmtree(tmp, ignore_errors=True)
            if "did not yield any result" in msg:
                log(f"  MAT {keep_name}: empty result — no CSV written")
                return None
            raise RuntimeError(f"MAT query {sfx} produced no CSV "
                               f"(rc={proc.returncode}): {msg[:300]}\n"
                               + "\n".join(list(tail)[-20:]))
        if len(csvs) > 1:
            log(f"WARNING: {sfx} produced {len(csvs)} CSV pages; keeping "
                f"{os.path.basename(csvs[0])} — result may be truncated")
        shutil.move(csvs[0], dst)
        shutil.rmtree(tmp, ignore_errors=True)
        return dst
