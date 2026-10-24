"""Job orchestration for exact structural shapes.

The Kotlin tool performs extraction and collapse.  This module owns the
per-class pipeline and atomic publication; binary formats live in
``shapes_artifact`` and JVM lifecycle in ``shapes_java``.  The server always
runs a prebuilt jar, never Gradle.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import struct
import tempfile

from .. import core
from .shapes_artifact import (
    grph_stats as _grph_stats,
    parse_shp1,
    validate_projection as _validate_projection,
)
from .shapes_java import run_java


_HERE = os.path.dirname(os.path.abspath(__file__))
_REPO = os.path.dirname(os.path.dirname(_HERE))
_DEFAULT_JAR = os.path.join(
    _REPO, "experiments", "shapes", "build", "libs", "shapes-experiment-all.jar")
_DEFAULT_RELAX_FILE = os.path.join(_REPO, "experiments", "shapes", "relax.tsv")

SHAPES_XMX = os.environ.get("SHAPES_XMX", "2g")
SHAPES_XMX_ANALYZE = os.environ.get("SHAPES_XMX_ANALYZE")
SHAPES_XMX_RETAINED = os.environ.get("SHAPES_XMX_RETAINED", "8g")
SHAPES_RELAX_FILE = os.environ.get("SHAPES_RELAX_FILE", _DEFAULT_RELAX_FILE)
PIPELINE_VERSION = 4
_METADATA_MAGIC = b"SMD1"
# Measured analyzer live set + GC headroom on the 257M-object scope; see
# experiments/shapes/README.md. Reference storage is added separately.
_BYTES_PER_OBJECT = 24


def analysis_options(scope="retained", normalize_maps=True):
    if scope not in ("retained", "reachable"):
        raise core.ApiError("bad_request", "scope must be retained or reachable", 400)
    if not isinstance(normalize_maps, bool):
        raise core.ApiError("bad_request", "normalizeMaps must be a boolean", 400)
    return {"scope": scope, "normalizeMaps": normalize_maps}


def shapes_jar():
    """Resolve the prebuilt tool, failing at request time with an action."""
    path = os.environ.get("SHAPES_JAR", _DEFAULT_JAR)
    if not os.path.isfile(path):
        raise core.ApiError(
            "bad_state",
            f"shapes tool jar not found: {path} — build it once with "
            "`cd experiments/shapes && ./gradlew fatJar` "
            "(or point SHAPES_JAR at a prebuilt shapes-experiment-all.jar)", 400)
    return path


def artifact_path(data_dir, cls):
    """Return the stable per-class artifact path (options replace on re-run)."""
    base = re.sub(r"[^A-Za-z0-9_$.-]", "_", cls)[-80:] or "cls"
    suffix = hashlib.sha1(cls.encode()).hexdigest()[:8]
    return os.path.join(data_dir, "shapes", f"{base}-{suffix}.shp1")


def _mem_available():
    """MemAvailable from /proc/meminfo in bytes (0 when unknown)."""
    try:
        with open("/proc/meminfo") as meminfo:
            for line in meminfo:
                if line.startswith("MemAvailable:"):
                    return int(line.split()[1]) * 1024
    except (OSError, ValueError, IndexError):
        pass
    return 0


def _analyze_xmx(grph, cls="?"):
    """Size analyzer heap from the actual graph and fail before swap thrash."""
    if SHAPES_XMX_ANALYZE:
        return SHAPES_XMX_ANALYZE
    objects, references = _grph_stats(grph)
    needed = objects * _BYTES_PER_OBJECT + 4 * references + (1 << 30)
    available = _mem_available()
    if available and needed > available * 0.95:
        raise RuntimeError(
            f"shapes analyze of {cls}: scope is {objects:,} objects / "
            f"{references:,} refs — needs ~{needed / 1e9:.1f} GB heap, only "
            f"{available / 1e9:.1f} GB RAM available. A class whose reachable "
            "closure is the whole heap is a service-graph anchor (SHAPES.md "
            "open point 1) — exact collapse pays off on bounded scopes "
            "(DTO/value classes). Override with SHAPES_XMX_ANALYZE if you "
            "really want to try.")
    megabytes = max(2048, int(needed // (1 << 20)))
    megabytes = (megabytes + 255) // 256 * 256
    return f"{megabytes}m"


def _run_java(job, jobs, abort, args, what, xmx=None):
    """Compatibility seam for job tests; implementation is in shapes_java."""
    scc_limit = (os.environ.get("SHAPES_MAX_SCC_MERGE", "1000000")
                 if what == "analyze" else None)
    return run_java(
        job, jobs, abort, args, what, jar=shapes_jar(), repo_root=_REPO,
        default_xmx=SHAPES_XMX, xmx=xmx, scc_merge_limit=scc_limit)


def _relax_file():
    """Resolve rules without silently ignoring an explicitly misspelled path."""
    configured = os.environ.get("SHAPES_RELAX_FILE")
    if configured and not os.path.isfile(configured):
        raise RuntimeError(f"SHAPES_RELAX_FILE does not exist: {configured}")
    if SHAPES_RELAX_FILE and os.path.isfile(SHAPES_RELAX_FILE):
        return SHAPES_RELAX_FILE
    return None


def analyze_class(job, jobs, runner, dump_dir, hprof, cls, dst, abort=None,
                  scope="retained", normalize_maps=True):
    """Run extract → collapse → validate → atomic publish for one class.

    All intermediates live in one unique temporary directory on the destination
    filesystem.  Failure preserves the previous artifact; success replaces it
    only after every aggregate and metadata setting has been validated.
    """
    analysis_options(scope, normalize_maps)
    out_dir = os.path.dirname(dst)
    os.makedirs(out_dir, exist_ok=True)
    relax_file = _relax_file()
    scc_merge_limit = int(os.environ.get("SHAPES_MAX_SCC_MERGE", "1000000"))
    if scc_merge_limit <= 0:
        raise RuntimeError("SHAPES_MAX_SCC_MERGE must be positive")

    with tempfile.TemporaryDirectory(prefix=".shapes-", dir=out_dir) as work:
        grph = os.path.join(work, "scope.grph")
        stats_path = os.path.join(work, "extract.json")
        relax_stats_path = os.path.join(work, "relax.json")
        tmp = os.path.join(work, "artifact.shp1")

        extract_args = [
            "mat-extract", "--hprof", hprof, "--class", cls,
            "--out", grph, "--scope", scope, "--stats", stats_path,
        ]
        if normalize_maps:
            extract_args.append("--normalize-maps")
        if relax_file:
            extract_args += ["--relax", relax_file]
        jobs.log(job, f"  scope={scope}; maps={'unordered entries' if normalize_maps else 'raw'}")
        with runner.index_read_hold(dump_dir, lambda message: jobs.log(job, message)):
            _run_java(job, jobs, abort, extract_args, "extract",
                      xmx=SHAPES_XMX_RETAINED if scope == "retained" else None)

        xmx = _analyze_xmx(grph, cls)
        objects, references = _grph_stats(grph)
        jobs.log(job, f"  shapes analyze: {objects:,} objects, {references:,} refs -> -Xmx{xmx}")
        analyze_args = [
            "analyze-grph", "--graph", grph, "--class", cls,
            "--out", tmp, "--whole-graph", "--relax-stats", relax_stats_path,
        ]
        if relax_file:
            analyze_args += ["--relax", relax_file]
            jobs.log(job, f"  relax rules: {relax_file}")
        _run_java(job, jobs, abort, analyze_args, "analyze", xmx=xmx)

        with open(stats_path, encoding="utf-8") as stats_file:
            metadata = json.load(stats_file)
        with open(relax_stats_path, encoding="utf-8") as relax_file_handle:
            relax_stats = json.load(relax_file_handle)
        if relax_file:
            with open(relax_file, "rb") as rules_file:
                relax_sha = hashlib.sha1(rules_file.read()).hexdigest()[:8]
            metadata["relaxation"] = {
                "file": os.path.basename(relax_file), "sha1": relax_sha,
                **relax_stats,
            }
        expected_projection = "unordered-entries-v1" if normalize_maps else "raw"
        if metadata.get("scope") != scope or metadata.get("mapProjection") != expected_projection:
            raise RuntimeError("shapes extraction settings do not match the request")
        metadata.update(pipelineVersion=PIPELINE_VERSION, sccMergeLimit=scc_merge_limit)
        _validate_projection(tmp, metadata)

        encoded = json.dumps(metadata, separators=(",", ":")).encode("utf-8")
        if len(encoded) > 0x7FFFFFFF:
            raise RuntimeError("shapes metadata is too large")
        with open(tmp, "ab") as artifact:
            artifact.write(_METADATA_MAGIC + struct.pack(">i", len(encoded)) + encoded)
            artifact.flush()
            os.fsync(artifact.fileno())
        os.replace(tmp, dst)
        jobs.log(job, f"  shapes artifact: {dst} ({os.path.getsize(dst)} bytes)")
