"""backend.localstore.compact — zstd compaction of MAT index files.

Mtime convention: a raw *.index whose mtime matches its .zst is untouched
since compaction and is just dropped; anything else is re-compressed (zstd,
checksum-verified before the raw is removed). Standalone — CI uses this
directly (backend/ci.py).
"""
import os
import subprocess

from .files import MARKER, MARKER_TEXT, _mtime, flock, raws_zsts

ZSTD = os.environ.get("ZSTD", "zstd")
LEVEL = int(os.environ.get("MATINDEX_LEVEL", "3"))     # zstd level for index compaction
THREADS = int(os.environ.get("MATINDEX_THREADS", "4"))


def _zstd(args):
    cmd = ["nice", "-n", "10", ZSTD] + args
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)} failed:\n{r.stderr[-500:]}")


def _compress_one(raw):
    zst = raw + ".zst"
    tmp = f"{zst}.tmp{os.getpid()}"
    try:
        _zstd([f"-{LEVEL}", f"-T{THREADS}", "-q", "-f", "-o", tmp, "--", raw])
        _zstd(["-t", "-q", "--", tmp])   # frame checksum verify before dropping the raw
        os.replace(tmp, zst)
        os.utime(zst, ns=(_mtime(raw), _mtime(raw)))
        os.remove(raw)
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)
    return os.path.getsize(zst)


def compact_dir(d, log=lambda m: None, progress=None, flock=flock):
    """(Re)compress the MAT indexes of one dump dir. `flock` is the
    cross-process lock helper (dump_dir, name) -> open exclusive-locked
    file; compact vs restore/MAT is serialized through it."""
    raws, _ = raws_zsts(d)
    archived = dropped = 0
    if raws:
        lf = flock(d, ".matindex.lock")   # guards compact vs restore across processes
        try:
            for i, raw in enumerate(raws):
                if progress:
                    progress(i, len(raws))
                zst = raw + ".zst"
                if os.path.exists(zst) and _mtime(zst) == _mtime(raw):
                    dropped += os.path.getsize(raw)
                    os.remove(raw)   # unchanged since archived — the .zst is still valid
                    continue
                log(f"  zstd -{LEVEL} {os.path.basename(raw)} "
                    f"({os.path.getsize(raw) / 1e9:.2f} GB) ...")
                archived += _compress_one(raw)
            with open(os.path.join(d, MARKER), "w") as f:
                f.write(MARKER_TEXT)
        finally:
            lf.close()
    log(f"  compact: {archived / 1e9:.2f} GB archived, "
        f"{dropped / 1e9:.2f} GB unchanged raw dropped")
    return archived, dropped
