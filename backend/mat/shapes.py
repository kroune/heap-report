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
import json
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
SHAPES_XMX_RETAINED = os.environ.get("SHAPES_XMX_RETAINED", "8g")
# class relaxation rules (Relax.kt): the versioned repo default, overridable
# for experiments; empty string disables relaxation entirely
SHAPES_RELAX_FILE = os.environ.get(
    "SHAPES_RELAX_FILE",
    os.path.join(_REPO, "experiments", "shapes", "relax.tsv"))
# v3: extraction filters java.lang.ref.Reference mechanics (GC semantics —
# soft/weak/phantom references do not retain), analyze-grph supports --relax
PIPELINE_VERSION = 3
_METADATA_MAGIC = b"SMD1"


def analysis_options(scope="retained", normalize_maps=True):
    if scope not in ("retained", "reachable"):
        raise core.ApiError("bad_request", "scope must be retained or reachable", 400)
    if not isinstance(normalize_maps, bool):
        raise core.ApiError("bad_request", "normalizeMaps must be a boolean", 400)
    return {"scope": scope, "normalizeMaps": normalize_maps}

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
    cmd = ["java", f"-Xmx{xmx or SHAPES_XMX}", "-XX:ActiveProcessorCount=4",
           "-jar", shapes_jar()] + args
    log(f"  shapes {what}: {' '.join(args[:2])} ...")
    tail = []
    env = os.environ.copy()
    if what == "analyze":
        env.setdefault("SHAPES_MAX_SCC_MERGE", "1000000")
    proc = subprocess.Popen(cmd, cwd=_REPO, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, env=env)

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


def analyze_class(job, jobs, runner, dump_dir, hprof, cls, dst, abort=None,
                  scope="retained", normalize_maps=True):
    """Full per-class shapes analysis: mat-extract -> analyze-grph -> atomic
    land at dst. The intermediate GRPH (unbounded size) always goes away.
    Raises on any failure — a failed analysis is never recorded."""
    analysis_options(scope, normalize_maps)
    out_dir = os.path.dirname(dst)
    os.makedirs(out_dir, exist_ok=True)
    grph = os.path.join(out_dir, f".{os.path.basename(dst)}.{os.getpid()}.grph")
    tmp = f"{dst}.tmp{os.getpid()}"
    stats_path = f"{grph}.json"
    try:
        relax_file = SHAPES_RELAX_FILE if SHAPES_RELAX_FILE and \
            os.path.exists(SHAPES_RELAX_FILE) else None
        extract_args = ["mat-extract", "--hprof", hprof, "--class", cls,
                        "--out", grph, "--scope", scope, "--stats", stats_path]
        if normalize_maps:
            extract_args.append("--normalize-maps")
        if relax_file:
            # level-3 field rules conceal edges at extraction time
            extract_args += ["--relax", relax_file]
        jobs.log(job, f"  scope={scope}; maps={'unordered entries' if normalize_maps else 'raw'}")
        with runner.index_read_hold(dump_dir, lambda m: jobs.log(job, m)):
            _run_java(job, jobs, abort, extract_args, "extract",
                      xmx=SHAPES_XMX_RETAINED if scope == "retained" else None)
        xmx = _analyze_xmx(grph, cls)
        n, e = _grph_stats(grph)
        jobs.log(job, f"  shapes analyze: {n:,} objects, {e:,} refs -> -Xmx{xmx}")
        relax_stats_path = f"{grph}.relax.json"
        analyze_args = ["analyze-grph", "--graph", grph, "--class", cls,
                        "--out", tmp, "--whole-graph",
                        "--relax-stats", relax_stats_path]
        if relax_file:
            analyze_args += ["--relax", relax_file]
            jobs.log(job, f"  relax rules: {relax_file}")
        _run_java(job, jobs, abort, analyze_args, "analyze", xmx=xmx)
        with open(stats_path) as f:
            metadata = json.load(f)
        with open(relax_stats_path) as f:
            relax_stats = json.load(f)
        if relax_file:
            with open(relax_file, "rb") as f:
                relax_sha = hashlib.sha1(f.read()).hexdigest()[:8]
            metadata["relaxation"] = {
                "file": os.path.basename(relax_file), "sha1": relax_sha,
                **relax_stats}
        if metadata.get("scope") != scope or metadata.get("mapProjection") != (
                "unordered-entries-v1" if normalize_maps else "raw"):
            raise RuntimeError("shapes extraction settings do not match the request")
        metadata.update(pipelineVersion=PIPELINE_VERSION,
                        sccMergeLimit=int(os.environ.get("SHAPES_MAX_SCC_MERGE", "1000000")))
        _validate_projection(tmp, metadata)
        encoded = json.dumps(metadata, separators=(",", ":")).encode("utf-8")
        # Same atomic file as SHP1: metadata cannot race an artifact replacement.
        with open(tmp, "ab") as f:
            f.write(_METADATA_MAGIC + struct.pack(">i", len(encoded)) + encoded)
        os.replace(tmp, dst)   # atomic land: readers see the full artifact or nothing
        jobs.log(job, f"  shapes artifact: {dst} "
                      f"({os.path.getsize(dst)} bytes)")
    finally:
        for p in (grph, tmp, stats_path, relax_stats_path):
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
    # reachableCount isn't emitted. aggregate() counts OBJECTS per shape
    # (count[sid]++ per member object), so objects collapsed by a shape is
    # simply `count` (collapsed INSTANCES = count / sccSize — that is what
    # the edge-total expansion uses). Exception: set-children quotient SCCs
    # count quotient-size per SCC instance (Shapes.kt), so the object total
    # needs the analyzer's setChildrenConcealedObjects counter added back
    # (_validate_projection does exactly that).
    metadata = None
    if off[0] < len(data):
        if data[off[0]:off[0] + 4] != _METADATA_MAGIC:
            raise RuntimeError(f"unknown SHP1 metadata in {path}")
        off[0] += 4
        length = take(">i")
        if length < 0 or off[0] + length != len(data):
            raise RuntimeError(f"truncated SHP1 metadata in {path}")
        metadata = json.loads(data[off[0]:].decode("utf-8"))
    return {"shapes": shapes, "edges": edges, "rootsHistogram": roots,
            "reachableCount": sum(s["count"] for s in shapes),
            "analysis": metadata}


def _validate_projection(path, metadata):
    """Cheap independent totals check before publishing projected data.
    Source refs and projected refs are deliberately NOT conflated. Relaxed
    classes (Relax.kt) conceal edges deliberately — the analyzer's exact
    counters are subtracted here, not assumed."""
    p = parse_shp1(path)
    rows = p["shapes"]
    relax = metadata.get("relaxation", {})
    totals = {
        # quotient SCCs (set-children) count quotient-size per instance, not
        # the true member count — the concealed members are accounted exactly
        # by the analyzer's counter, like concealed edges
        "objects": p["reachableCount"] + relax.get("setChildrenConcealedObjects", 0),
        "roots": sum(r[1] for r in p["rootsHistogram"]),
        "shallowTotal": sum(r["shallowTotal"] for r in rows),
    }
    accounted = sum(r["internalEdges"] * (r["count"] // r["sccSize"]) for r in rows)
    for source, target, mult in p["edges"]:
        r = rows[source]
        accounted += mult * (r["count"] if r["edgesPerMember"] else r["count"] // r["sccSize"])
    concealed = relax.get("classOnlyHiddenEdges", 0) + relax.get("setChildrenHiddenEdges", 0)
    totals["projectedEdges"] = accounted + concealed
    for key, actual in totals.items():
        if actual != metadata[key]:
            raise RuntimeError(f"shapes {key}: emitted {actual} != extracted {metadata[key]}")
