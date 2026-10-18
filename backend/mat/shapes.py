"""backend/mat/shapes — the exact-structural-collapse (SHAPES.md) gateway.

The algorithm itself lives in the Kotlin experiment (experiments/shapes); the
backend only runs the prebuilt fat jar as a subprocess (never gradle — the
jar is a build artifact, resolved via SHAPES_JAR or the default build/libs
path) and parses the SHP1 binary artifact into the served JSON payload.

Two subprocess steps per class:
  1. mat-extract  — BFS over MAT's outbound index from all instances of the
     class (the indexes must sit next to the hprof: restore + shared index
     lock via MatRunner.index_read_hold) -> a dense GRPH graph file
     (potentially gigabytes — deleted right after step 2).
  2. analyze-grph — the collapse pipeline -> SHP1 artifact, landed atomically
     at data/shapes/<key>.shp1.

Artifact naming is deterministic from the class name (sanitized + sha1
suffix), so the query path needs no index file.
"""
from __future__ import annotations

import hashlib
import os
import re
import struct
import subprocess
import threading
import time

from .. import core

_HERE = os.path.dirname(os.path.abspath(__file__))          # backend/mat/
_REPO = os.path.dirname(os.path.dirname(_HERE))             # repo root

_DEFAULT_JAR = os.path.join(_REPO, "experiments", "shapes", "build", "libs",
                            "shapes-experiment-all.jar")
SHAPES_XMX = os.environ.get("SHAPES_XMX", "2g")   # extractor heap (index-backed, 2g works at full-heap scale)
# analyzer heap is SIZED from the extracted graph (below); this is the override
SHAPES_XMX_ANALYZE = os.environ.get("SHAPES_XMX_ANALYZE")

_KINDS = ["leaf", "node", "scc", "scc-unique"]


def shapes_jar():
    """The prebuilt shapes fat jar: SHAPES_JAR wins, else the gradle fatJar
    output. Missing = a clear instruction, not a stack trace."""
    p = os.environ.get("SHAPES_JAR", _DEFAULT_JAR)
    if not os.path.exists(p):
        raise core.ApiError(
            "bad_state",
            f"shapes tool jar not found: {p} — build it once with "
            "`cd experiments/shapes && ./gradlew fatJar` "
            "(or point SHAPES_JAR at a prebuilt shapes-experiment-all.jar)", 400)
    return p


def artifact_path(data_dir, cls):
    """data/shapes/<sanitized>-<sha1:8>.shp1 — deterministic from the class
    name (no index file needed), collision-free via the hash suffix."""
    base = re.sub(r"[^A-Za-z0-9_$.-]", "_", cls)[-80:] or "cls"
    h = hashlib.sha1(cls.encode()).hexdigest()[:8]
    return os.path.join(data_dir, "shapes", f"{base}-{h}.shp1")


def _grph_stats(path):
    """(objects n, refs E) from the GRPH header + file size (Graph.kt layout,
    big-endian): magic i32, sourceN i64, class table (i32 len + utf8 bytes)
    x i32 count, i32 n, then n rows of 16 B + 4 B/ref — so
    E = (fileSize - header - 16n) / 4. The graph body is never read."""
    with open(path, "rb") as f:
        head = f.read(16)
        if len(head) < 16 or struct.unpack_from(">i", head, 0)[0] != 0x47525048:
            raise RuntimeError(f"bad GRPH magic in {path}")
        (cc,) = struct.unpack_from(">i", head, 12)
        hdr = 16
        for _ in range(cc):
            raw = f.read(4)
            if len(raw) < 4:
                raise RuntimeError(f"truncated GRPH class table in {path}")
            (nl,) = struct.unpack(">i", raw)
            if len(f.read(nl)) < nl:
                raise RuntimeError(f"truncated GRPH class table in {path}")
            hdr += 4 + nl
        raw = f.read(4)
        if len(raw) < 4:
            raise RuntimeError(f"truncated GRPH header in {path}")
        (n,) = struct.unpack(">i", raw)
        hdr += 4
    e = max(0, (os.path.getsize(path) - hdr - 16 * n) // 4)
    return n, e


def _mem_available():
    """MemAvailable from /proc/meminfo in bytes (0 when unknown)."""
    try:
        with open("/proc/meminfo") as f:
            for line in f:
                if line.startswith("MemAvailable:"):
                    return int(line.split()[1]) * 1024
    except OSError:
        pass
    return 0


# analyzer live set after the footprint pass: graph 10n+4E (2 B class idx +
# 4 B packed shallow + 4 B offsets + 4 B/ref) + Tarjan 4n (index) + comps
# ~5n + shapeOfComp ~2n + shapes/cons ~2n + GC headroom — measured against
# the 257M-object scope: OOM at 20n (8.7g), completes at 24n (9.6g)
_BYTES_PER_OBJECT = 24


def _analyze_xmx(grph, cls="?"):
    """The analyzer's heap, sized by what the extraction ACTUALLY reached
    (n, E from the GRPH header + file size) — a fixed -Xmx just OOMs on big
    scopes. SHAPES_XMX_ANALYZE overrides unconditionally. A scope that needs
    more than ~85% of AVAILABLE RAM fails FAST with a clear message — running
    it anyway is a GC/swap thrash that never finishes (observed: 257M-object
    scope asking -Xmx12800m on a 15 GB machine, 1h+ at 100% CPU in GC)."""
    if SHAPES_XMX_ANALYZE:
        return SHAPES_XMX_ANALYZE
    n, e = _grph_stats(grph)
    need = n * _BYTES_PER_OBJECT + 4 * e + (1 << 30)
    avail = _mem_available()
    # the JVM is the only heavy process: anything up to ~available is fine,
    # only a clear oversubscription thrashes (12.8g asked on 8.5g available
    # ran 1h+ at 100% CPU in GC before OOM)
    if avail and need > avail * 0.95:
        raise RuntimeError(
            f"shapes analyze of {cls}: scope is {n:,} objects / {e:,} refs — "
            f"needs ~{need / 1e9:.1f} GB heap, only {avail / 1e9:.1f} GB RAM "
            f"available. A class whose reachable closure is the whole heap is "
            f"a service-graph anchor (SHAPES.md open point 1) — exact collapse "
            f"pays off on bounded scopes (DTO/value classes). Override with "
            f"SHAPES_XMX_ANALYZE if you really want to try.")
    mb = max(2048, int(need // (1 << 20)))
    mb = (mb + 255) // 256 * 256
    return f"{mb}m"


def _run_java(job, jobs, abort, args, what, xmx=None):
    """One java subprocess of the shapes tool; stdout streams into the job
    log (capped there). abort (the dump's cooperative-cancel flag) kills the
    JVM and raises core.Aborted. Any nonzero exit raises with the tail."""
    log = lambda m: jobs.log(job, m)
    cmd = ["java", f"-Xmx{xmx or SHAPES_XMX}", "-jar", shapes_jar()] + args
    log(f"  shapes {what}: {' '.join(args[:2])} ...")
    tail = []
    proc = subprocess.Popen(cmd, cwd=_REPO, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True)

    def drain():   # stream into the job log; keep our own short tail for errors
        for line in proc.stdout:
            line = line.rstrip("\n")
            tail.append(line)
            del tail[:-50]
            jobs.log(job, line)

    t = threading.Thread(target=drain, daemon=True)
    t.start()
    try:
        while True:
            if abort is not None and abort.is_set():
                proc.kill()
                proc.wait()
                raise core.Aborted(f"shapes {what} aborted")
            try:
                proc.wait(timeout=2)
                break
            except subprocess.TimeoutExpired:
                pass
    finally:
        t.join()
    if proc.returncode != 0:
        hint = ("\n  hint: the analyzer needs ~24 B/reachable object + 4 B/ref "
                "— set SHAPES_XMX_ANALYZE (e.g. 10g for a 200M-object scope)"
                if any("OutOfMemoryError" in l for l in tail) else "")
        raise RuntimeError(f"shapes {what} failed (rc={proc.returncode}):\n"
                           + "\n".join(tail[-20:]) + hint)


def analyze_class(job, jobs, runner, dump_dir, hprof, cls, dst, abort=None):
    """Full per-class shapes analysis: mat-extract -> analyze-grph -> atomic
    land at dst. The intermediate GRPH (unbounded size) always goes away.
    Raises on any failure — a failed analysis is never recorded."""
    out_dir = os.path.dirname(dst)
    os.makedirs(out_dir, exist_ok=True)
    grph = os.path.join(out_dir, f".{os.path.basename(dst)}.{os.getpid()}.grph")
    tmp = f"{dst}.tmp{os.getpid()}"
    try:
        with runner.index_read_hold(dump_dir, lambda m: jobs.log(job, m)):
            _run_java(job, jobs, abort,
                      ["mat-extract", "--hprof", hprof, "--class", cls,
                       "--out", grph], "extract")
        xmx = _analyze_xmx(grph, cls)
        n, e = _grph_stats(grph)
        jobs.log(job, f"  shapes analyze: {n:,} objects, {e:,} refs -> -Xmx{xmx}")
        _run_java(job, jobs, abort,
                  ["analyze-grph", "--graph", grph, "--class", cls,
                   "--out", tmp], "analyze", xmx=xmx)
        os.replace(tmp, dst)   # atomic land: readers see the full artifact or nothing
        jobs.log(job, f"  shapes artifact: {dst} "
                      f"({os.path.getsize(dst)} bytes)")
    finally:
        for p in (grph, tmp):
            try:
                os.remove(p)
            except OSError:
                pass


def parse_shp1(path):
    """SHP1 binary artifact (Emit.kt layout, big-endian) -> the served JSON
    payload. The digest is rendered as one 128-bit hex string."""
    with open(path, "rb") as f:
        data = f.read()
    if data[:4] != b"SHP1":
        raise RuntimeError(f"not a SHP1 artifact: {path}")
    off = [4]

    def take(fmt_):
        n = struct.calcsize(fmt_)
        v = struct.unpack_from(fmt_, data, off[0])
        off[0] += n
        return v if len(v) > 1 else v[0]

    labels = []
    for _ in range(take(">i")):
        n = take(">i")
        labels.append(data[off[0]:off[0] + n].decode("utf-8"))
        off[0] += n
    shapes = []
    for sid in range(take(">i")):
        hi, lo = take(">q"), take(">q")
        digest = (hi & 0xFFFFFFFFFFFFFFFF).to_bytes(8, "big").hex() + \
                 (lo & 0xFFFFFFFFFFFFFFFF).to_bytes(8, "big").hex()
        shapes.append({
            "id": sid,
            "digest": digest,
            "kind": _KINDS[take(">b")],
            "label": labels[take(">i")],
            "count": take(">q"),
            "shallowTotal": take(">q"),
            "shallowMin": take(">q"),
            "shallowMax": take(">q"),
            "roots": take(">q"),
            "sccSize": take(">i"),
            "internalEdges": take(">i"),
            "edgesPerMember": bool(take(">b")),
        })
    edges = [[take(">i"), take(">i"), take(">q")] for _ in range(take(">i"))]
    roots = [[take(">i"), take(">q")] for _ in range(take(">i"))]
    roots.sort(key=lambda r: -r[1])
    # reachableCount isn't emitted. aggregate() counts OBJECTS per shape for
    # every kind (count[sid]++ per member object), so objects collapsed by a
    # shape is simply `count` (collapsed INSTANCES = count / sccSize — that is
    # what the edge-total expansion uses). Summing count × sccSize inflated
    # every multi-member SCC by its member count (313.8M vs |R| = 256.9M).
    return {"shapes": shapes, "edges": edges, "rootsHistogram": roots,
            "reachableCount": sum(s["count"] for s in shapes)}
