"""Job orchestration for exact structural shapes.

The Kotlin tool performs extraction and collapse.  This module owns the
heap-wide pipeline and atomic publication; binary formats live in
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
    parse_shp1,
    validate_projection as _validate_projection,
)
from .shapes_java import run_java


_HERE = os.path.dirname(os.path.abspath(__file__))
_REPO = os.path.dirname(os.path.dirname(_HERE))
_DEFAULT_JAR = os.path.join(
    _REPO, "experiments", "shapes", "build", "libs", "shapes-experiment-all.jar")
_DEFAULT_RELAX_FILE = os.path.join(_REPO, "experiments", "shapes", "relax.tsv")

# One JVM owns extraction, the in-memory graph, and analysis. Size that
# combined heap explicitly.
SHAPES_XMX_ANALYZE = os.environ.get("SHAPES_XMX_ANALYZE", "8g")
SHAPES_RELAX_FILE = os.environ.get("SHAPES_RELAX_FILE", _DEFAULT_RELAX_FILE)
PIPELINE_VERSION = 6
_METADATA_MAGIC = b"SMD1"


def analysis_options(extraction_mode="retained"):
    if extraction_mode not in ("retained", "reachable"):
        raise core.ApiError(
            "bad_request", "extraction mode must be retained or reachable", 400)
    return {"extractionMode": extraction_mode}


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


def analyze_class(job, jobs, runner, dump_dir, hprof, cls, dst, abort=None,
                  extraction_mode="retained"):
    """Run direct MAT extract + collapse → validate → atomic publish.

    ``cls=None`` selects the complete non-class heap (``extraction_mode='heap'``).
    The class-scoped form remains available to the standalone diagnostics. All
    intermediates live in one unique temporary directory on the destination
    filesystem. Failure preserves the previous artifact; success replaces it
    only after every aggregate and metadata setting has been validated.
    """
    if cls is None:
        if extraction_mode != "heap":
            raise core.ApiError("bad_request", "heap-wide extraction mode must be heap", 400)
    else:
        analysis_options(extraction_mode)
    out_dir = os.path.dirname(dst)
    os.makedirs(out_dir, exist_ok=True)
    relax_file = _relax_file()
    with tempfile.TemporaryDirectory(prefix=".shapes-", dir=out_dir) as work:
        stats_path = os.path.join(work, "extract.json")
        relax_stats_path = os.path.join(work, "relax.json")
        tmp = os.path.join(work, "artifact.shp1")

        analyze_args = [
            "mat-analyze", "--hprof", hprof, "--out", tmp,
            "--scope", extraction_mode, "--stats", stats_path,
            "--relax-stats", relax_stats_path,
        ]
        analyze_args += ["--whole-heap"] if cls is None else ["--class", cls]
        if relax_file:
            analyze_args += ["--relax", relax_file]
            jobs.log(job, f"  relax rules: {relax_file}")
        jobs.log(job, f"  extractionMode={extraction_mode}")
        jobs.log(job, f"  direct MAT extract + analyze -> -Xmx{SHAPES_XMX_ANALYZE}")
        with runner.index_read_hold(dump_dir, lambda message: jobs.log(job, message)):
            _run_java(job, jobs, abort, analyze_args, "analyze", xmx=SHAPES_XMX_ANALYZE)

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
        extracted_mode = metadata.get("extractionMode")
        if not isinstance(extracted_mode, dict) or extracted_mode.get("type") != extraction_mode:
            raise RuntimeError("shapes extraction settings do not match the request")
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
