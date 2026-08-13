#!/usr/bin/env python3
"""Compact storage for MAT index files (the `*.index` set next to an .hprof).

MAT needs its indexes as raw files next to the dump, but only while a query
runs — at rest they are ~19-21 GB per 17 GB dump here and compress to ~50%
with zstd -3 (o2ret/o2c even to ~1-3%). This module keeps them as
`*.index.zst` and restores the raw files on demand before a MAT run.

Mtime convention: `X.index.zst` carries the mtime of the raw `X.index` it was
compressed from. A raw file whose mtime still matches its .zst was not touched
by MAT and can simply be dropped on re-compact (the archive is still valid),
which makes re-compacting after an analysis session nearly free. MAT only
rewrites an index when it recomputes it, so in practice just the tiny
log.index gets recompressed.

Safety: never invoke ParseHeapDump.sh directly against a compacted dump —
MAT would treat the missing indexes as "not parsed yet" and re-parse the whole
hprof (~40 min, full 20 GB back). Go through analyze_dump.py / serve.py /
compact.py, which restore first. compact.py drops an INDEXES-COMPACTED.txt
reminder into the dump dir.
"""
import fcntl, glob, os, subprocess, threading

ZSTD = os.environ.get("ZSTD", "zstd")
LEVEL = int(os.environ.get("MATINDEX_LEVEL", "3"))   # 3 ~= best time/ratio: the dominant files
# (inbound/outbound/domOut) gain only 2-5pp from higher levels but compress 6-10x slower;
# high levels only pay off on the small files (o2c/o2ret). Measured: L12 = 10.1 GB/dump
# at ~30 MB/s, L3 = ~11.0 GB at ~600 MB/s. Use --level 12..19 for archival density.
THREADS = int(os.environ.get("MATINDEX_THREADS", "4"))  # zstd MT barely scales past this at high levels
MARKER = "INDEXES-COMPACTED.txt"

MARKER_TEXT = """\
The MAT index files in this directory are stored compressed (*.index.zst).
MAT itself needs them raw; they are restored automatically when a query runs
via analyze_dump.py or serve.py, and re-compressed
when the analysis session goes idle.

Manual control:
  python3 compact.py <this-dir>      # compress
  python3 compact.py --restore <dir> # decompress

Do NOT run ParseHeapDump.sh directly against the .hprof while compacted —
MAT would mistake the missing indexes for an unparsed dump and re-parse
everything.
"""


def _lock(dump_dir):
    f = open(os.path.join(dump_dir, ".matindex.lock"), "a")
    fcntl.flock(f, fcntl.LOCK_EX)
    return f


def _mtime(path):
    return os.stat(path).st_mtime_ns


def raws_zsts(dump_dir):
    raws = sorted(glob.glob(os.path.join(dump_dir, "*.index")))
    zsts = sorted(glob.glob(os.path.join(dump_dir, "*.index.zst")))
    return raws, zsts


def has_compacted(dump_dir):
    """True once this dump has (or had) compressed indexes — i.e. the
    compacted state should be restored/maintained automatically."""
    return bool(glob.glob(os.path.join(dump_dir, "*.index.zst"))) or \
        os.path.exists(os.path.join(dump_dir, MARKER))


def needs_restore(dump_dir):
    """Fast path check: any .zst whose raw file is currently missing."""
    for z in glob.glob(os.path.join(dump_dir, "*.index.zst")):
        if not os.path.exists(z[:-4]):
            return True
    return False


def _zstd(args, log):
    cmd = ["nice", "-n", "10", ZSTD] + args
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)} failed:\n{r.stderr[-500:]}")


def _compress_one(raw, level, log):
    zst = raw + ".zst"
    tmp = f"{zst}.tmp{os.getpid()}"
    try:
        _zstd([f"-{level}", f"-T{THREADS}", "-q", "-f", "-o", tmp, "--", raw], log)
        _zstd(["-t", "-q", "--", tmp], log)   # frame checksum verify before dropping the raw
        os.replace(tmp, zst)
        os.utime(zst, ns=(_mtime(raw), _mtime(raw)))
        os.remove(raw)
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)
    return os.path.getsize(zst)


def _decompress_one(zst, log):
    raw = zst[:-4]
    tmp = f"{raw}.tmp{os.getpid()}"
    try:
        _zstd(["-d", f"-T{THREADS}", "-q", "-f", "-o", tmp, "--", zst], log)  # -d verifies checksums
        os.replace(tmp, raw)
        os.utime(raw, ns=(_mtime(zst), _mtime(zst)))   # match the convention: raw mtime == zst mtime
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)
    return raw


def compact(dump_dir, level=LEVEL, log=print, should_stop=None):
    """Compress every raw *.index to .zst (raw files already archived with a
    matching mtime are just dropped). Aborts between files when should_stop()
    becomes true. Returns (archived_bytes, raw_bytes_dropped)."""
    raws, _ = raws_zsts(dump_dir)
    archived = dropped = 0
    if not raws:
        return archived, dropped
    lock = _lock(dump_dir)
    try:
        for raw in raws:
            if should_stop and should_stop():
                log("  compact: interrupted, will resume next idle period")
                break
            zst = raw + ".zst"
            if os.path.exists(zst) and _mtime(zst) == _mtime(raw):
                dropped += os.path.getsize(raw)
                os.remove(raw)   # unchanged since archived — the .zst is still valid
                continue
            log(f"  zstd -{level} {os.path.basename(raw)} "
                f"({os.path.getsize(raw) / 1e9:.2f} GB) ...")
            archived += _compress_one(raw, level, log)
        with open(os.path.join(dump_dir, MARKER), "w") as f:
            f.write(MARKER_TEXT)
    finally:
        lock.close()
    return archived, dropped


def restore(dump_dir, log=print, jobs=4):
    """Decompress all *.index.zst whose raw file is missing. Keeps the .zst
    files (still valid — re-compact then just drops the raws). No-op fast path
    when nothing is missing. Returns number of files restored."""
    if not needs_restore(dump_dir):
        return 0
    lock = _lock(dump_dir)
    try:
        todo = [z for z in sorted(glob.glob(os.path.join(dump_dir, "*.index.zst")))
                if not os.path.exists(z[:-4])]
        if not todo:
            return 0
        total = sum(os.path.getsize(z) for z in todo)
        log(f"  restoring {len(todo)} compacted MAT index files "
            f"({total / 1e9:.1f} GB compressed) ...")

        # round-robin handout keeps it simple: each thread takes every Nth file
        n = max(1, min(jobs, len(todo)))
        errors = []

        def work(subset):
            for z in subset:
                try:
                    _decompress_one(z, log)
                except Exception as e:   # noqa: BLE001 - re-raised on the main thread
                    errors.append(e)

        threads = [threading.Thread(target=work, args=(todo[i::n],)) for i in range(n)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        if errors:
            raise errors[0]
        return len(todo)
    finally:
        lock.close()
