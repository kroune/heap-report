"""backend.localstore.files — on-disk artifact inspection and drop helpers.

Pure filesystem predicates/mutations over a dump dir; no state, no jobs.
The machine's observations (machine.Obs) are built from these. On-disk
contract: LAYOUT.md.
"""
import fcntl
import glob
import os

MARKER = "INDEXES-COMPACTED.txt"
UNTAR = ".untar"   # staging dir inside the dump dir: tar members land here and
                   # are moved to their final paths only after the untar
                   # succeeded AND the set validated — final paths always hold
                   # complete files, never a truncated mid-extraction artifact

MARKER_TEXT = """\
The MAT index files in this directory are stored compressed (*.index.zst).
MAT itself needs them raw; they are restored automatically when a query runs
and re-compressed when the analysis session goes idle.

Do NOT run ParseHeapDump.sh directly against the .hprof while compacted —
MAT would mistake the missing indexes for an unparsed dump and re-parse
everything.
"""


def _mtime(path):
    return os.stat(path).st_mtime_ns


def flock(dump_dir, name):
    """Open `<dump_dir>/<name>` under an EXCLUSIVE flock (cross-process
    serialization for meta.json, index commits, compact/restore)."""
    f = open(os.path.join(dump_dir, name), "a")
    fcntl.flock(f, fcntl.LOCK_EX)
    return f


def _keep(p):
    """MAT parse debris (crashed/in-progress parse) is not an index set."""
    n = os.path.basename(p)
    return not n.endswith(".lock.index") and ".temp." not in n


def raws_zsts(dump_dir):
    raws = [p for p in sorted(glob.glob(os.path.join(dump_dir, "*.index"))) if _keep(p)]
    zsts = [p for p in sorted(glob.glob(os.path.join(dump_dir, "*.index.zst"))) if _keep(p)]
    return raws, zsts


def has_data(dump_dir):
    """The extracted data bundle (the overview tabs work off these two)."""
    return os.path.exists(os.path.join(dump_dir, "data", "histogram.csv")) and \
        os.path.exists(os.path.join(dump_dir, "data", "dominator_by_class.csv"))


def has_compacted(dump_dir):
    return bool(glob.glob(os.path.join(dump_dir, "*.index.zst"))) or \
        os.path.exists(os.path.join(dump_dir, MARKER))


def parse_debris(d):
    """Files left by an interrupted local MAT parse (it removes them on
    success; MatRunner._pin_hprof clears them before runs)."""
    out = []
    for p in glob.glob(os.path.join(d, "*.index")) + \
            glob.glob(os.path.join(d, "*.index.zst")):
        n = os.path.basename(p)
        if n.endswith(".lock.index") or ".temp." in n or n.endswith(".lock.index.zst"):
            out.append(p)
    return out


def drop_untrusted_raws(d, log=lambda m: None):
    """When parse debris is present, the surviving raw *.index set comes from
    an interrupted local parse and is partial/untrusted — drop it (plus the
    debris) so the prebuilt remote set is fetched instead. No-op without
    debris or when a compacted (.zst) set exists (raws are then restores of
    a valid set)."""
    raws, zsts = raws_zsts(d)
    debris = parse_debris(d)
    if not debris or zsts:
        return False
    for p in raws + debris:
        os.remove(p)
    if raws:
        log(f"  dropped {len(raws)} untrusted partial index file(s) "
            "from the interrupted local parse")
    return True


def drop_index_set(d, log=lambda m: None):
    """Drop the ENTIRE index set (raws + zsts + parse debris): the set is
    untrusted as a whole — e.g. after a corrupt member was found and deleted,
    the partial remainder is unusable and MAT must never run against it (it
    would reparse the whole dump). The set is then re-acquired from scratch
    (remote re-download or local parse). Returns the number of files removed."""
    raws, zsts = raws_zsts(d)
    debris = parse_debris(d)
    for p in raws + zsts + debris:
        os.remove(p)
    n = len(raws) + len(zsts) + len(debris)
    if n:
        log(f"  dropped {n} index file(s) — the set is re-acquired from scratch")
    return n
