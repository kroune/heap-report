"""Job orchestration for exact structural shapes.

The Kotlin tool reads the HPROF file directly (no MAT indexes) and performs
extraction and collapse in one JVM.  This module owns the heap-wide pipeline
and atomic publication; binary formats live in ``shapes_artifact`` and JVM
lifecycle in ``shapes_java``.  The server always runs a prebuilt jar, never
Gradle.
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
    parse_shp1,
    validate_projection as _validate_projection,
)
from .shapes_java import run_java


_HERE = os.path.dirname(os.path.abspath(__file__))
_REPO = os.path.dirname(os.path.dirname(_HERE))
_DEFAULT_JAR = os.path.join(
    _REPO, "experiments", "shapes", "build", "libs", "shapes-experiment-all.jar")
_DEFAULT_RELAX_FILE = os.path.join(_REPO, "experiments", "shapes", "relax.tsv")

# One JVM owns extraction, the off-heap graph, and analysis. The graph lives
# in file-backed mappings outside the Java heap, so a small heap suffices even
# for multi-hundred-million-object dumps.
SHAPES_XMX_ANALYZE = os.environ.get("SHAPES_XMX_ANALYZE", "2g")
SHAPES_RELAX_FILE = os.environ.get("SHAPES_RELAX_FILE", _DEFAULT_RELAX_FILE)
PIPELINE_VERSION = 9
_METADATA_MAGIC = b"SMD1"


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


def artifact_path(data_dir, cls=None):
    """Stable artifact path; the server uses one heap-wide artifact per dump."""
    if cls is None:
        return os.path.join(data_dir, "shapes", "heap.shp1")
    base = re.sub(r"[^A-Za-z0-9_$.-]", "_", cls)[-80:] or "cls"
    suffix = hashlib.sha1(cls.encode()).hexdigest()[:8]
    return os.path.join(data_dir, "shapes", f"{base}-{suffix}.shp1")


def _run_java(job, jobs, abort, args, what, xmx=None):
    """Compatibility seam for job tests; implementation is in shapes_java."""
    return run_java(
        job, jobs, abort, args, what, jar=shapes_jar(), repo_root=_REPO,
        default_xmx=SHAPES_XMX_ANALYZE, xmx=xmx)


def _relax_file():
    """Resolve rules without silently ignoring an explicitly misspelled path."""
    configured = os.environ.get("SHAPES_RELAX_FILE")
    if configured and not os.path.isfile(configured):
        raise RuntimeError(f"SHAPES_RELAX_FILE does not exist: {configured}")
    if SHAPES_RELAX_FILE and os.path.isfile(SHAPES_RELAX_FILE):
        return SHAPES_RELAX_FILE
    return None


def _publication_metadata(stats, cls, extraction_mode):
    """Translate the tool's extraction stats into publication metadata.

    The validator cross-checks artifact aggregates against these totals, so
    they must come from the extractor's independent counters, never from the
    artifact itself. The raw stats ride along for diagnostics.
    """
    expected_scope = "whole-heap" if cls is None else cls
    if stats.get("scope") != expected_scope:
        raise RuntimeError("shapes extraction scope does not match the request")
    for key in ("objects", "shallowTotal", "edges", "roots"):
        value = stats.get(key)
        if not isinstance(value, int) or isinstance(value, bool) or value < 0:
            raise RuntimeError(f"shapes extraction stats missing {key}")
    mode = {"type": "heap"} if cls is None else {
        "type": extraction_mode, "className": cls}
    return {
        "extractionMode": mode,
        "objects": stats["objects"],
        "roots": stats["roots"],
        "shallowTotal": stats["shallowTotal"],
        "projectedEdges": stats["edges"],
        # Raw non-null references seen in the dump; the UI shows this as
        # "source refs in heap" next to the projected count.
        "sourceEdges": stats.get("rawNonNullRefs", 0),
        "extraction": stats,
    }


def analyze(job, jobs, hprof, cls, dst, abort=None, extraction_mode="reachable"):
    """Run direct HPROF extract + collapse → validate → atomic publish.

    ``cls=None`` selects the complete live non-class heap
    (``extraction_mode='heap'``); a class name selects its reachable closure
    (CLI diagnostics only). All intermediates live in one unique temporary
    directory on the destination filesystem. Failure preserves the previous
    artifact; success replaces it only after every aggregate and metadata
    setting has been validated.
    """
    expected_mode = "heap" if cls is None else "reachable"
    if extraction_mode != expected_mode:
        raise core.ApiError(
            "bad_request", f"extraction mode must be {expected_mode}", 400)
    out_dir = os.path.dirname(dst)
    os.makedirs(out_dir, exist_ok=True)
    relax_file = _relax_file()
    with tempfile.TemporaryDirectory(prefix=".shapes-", dir=out_dir) as work:
        stats_path = os.path.join(work, "extract.json")
        relax_stats_path = os.path.join(work, "relax.json")
        tmp = os.path.join(work, "artifact.shp1")

        analyze_args = [
            "hprof-analyze", "--hprof", hprof, "--out", tmp,
            "--stats", stats_path, "--relax-stats", relax_stats_path,
        ]
        analyze_args += ["--whole-heap"] if cls is None else ["--class", cls]
        if relax_file:
            analyze_args += ["--relax", relax_file]
            jobs.log(job, f"  relax rules: {relax_file}")
        jobs.log(job, f"  extractionMode={extraction_mode}")
        jobs.log(job, f"  direct HPROF extract + analyze -> -Xmx{SHAPES_XMX_ANALYZE}")
        _run_java(job, jobs, abort, analyze_args, "analyze", xmx=SHAPES_XMX_ANALYZE)

        with open(stats_path, encoding="utf-8") as stats_file:
            stats = json.load(stats_file)
        with open(relax_stats_path, encoding="utf-8") as relax_file_handle:
            relax_stats = json.load(relax_file_handle)
        metadata = _publication_metadata(stats, cls, extraction_mode)
        if relax_file:
            with open(relax_file, "rb") as rules_file:
                relax_sha = hashlib.sha1(rules_file.read()).hexdigest()[:8]
            metadata["relaxation"] = {
                "file": os.path.basename(relax_file), "sha1": relax_sha,
                **relax_stats,
            }
        metadata.update(pipelineVersion=PIPELINE_VERSION)
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
