"""Readers and consistency checks for the shapes wire artifacts.

SHP1 and GRPH are big-endian formats produced by the Kotlin shapes tool.  This
module is deliberately independent of the job engine: corrupt input is reported
as :class:`ShapesArtifactError`, and no subprocess or dump-store state is needed
to inspect an artifact.
"""
from __future__ import annotations

import json
import os
import struct


_METADATA_MAGIC = b"SMD1"
_KINDS = ("leaf", "node", "scc", "scc-unique")
_SHAPE_ROW_SIZE = 70
_EDGE_ROW_SIZE = 16
_ROOT_ROW_SIZE = 12


class ShapesArtifactError(RuntimeError):
    """A malformed, truncated, or internally inconsistent shapes artifact."""


class _Reader:
    def __init__(self, path):
        self.path = path
        self.file = open(path, "rb")
        self.size = os.fstat(self.file.fileno()).st_size
        self.offset = 0

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        self.file.close()

    def read(self, size, what="data"):
        if size < 0 or size > self.size - self.offset:
            raise ShapesArtifactError(
                f"truncated {what} in {self.path} at byte {self.offset}")
        data = self.file.read(size)
        if len(data) != size:
            raise ShapesArtifactError(
                f"truncated {what} in {self.path} at byte {self.offset}")
        self.offset += size
        return data

    def unpack(self, fmt, what="value"):
        size = struct.calcsize(fmt)
        values = struct.unpack(fmt, self.read(size, what))
        return values if len(values) > 1 else values[0]

    def count(self, what, row_size):
        value = self.unpack(">i", f"{what} count")
        if value < 0 or (row_size and value > (self.size - self.offset) // row_size):
            raise ShapesArtifactError(f"invalid {what} count {value} in {self.path}")
        return value

    def skip(self, size, what="data"):
        self.read(size, what)


def parse_shp1(path):
    """Parse a SHP1 artifact into the JSON payload served by the engine.

    Parsing is streaming and validates every count, index, enum, and trailer
    boundary.  ``reachableCount`` is the real scope population: quotient SCCs
    conceal members from the visible shape counts, but their exact counter is
    stored in the SMD1 relaxation metadata and must be added back here.
    """
    with _Reader(path) as reader:
        if reader.read(4, "SHP1 magic") != b"SHP1":
            raise ShapesArtifactError(f"not a SHP1 artifact: {path}")

        labels = []
        for _ in range(reader.count("label", 4)):
            size = reader.unpack(">i", "label length")
            raw = reader.read(size, "label")
            try:
                labels.append(raw.decode("utf-8"))
            except UnicodeDecodeError as exc:
                raise ShapesArtifactError(f"invalid UTF-8 label in {path}") from exc

        shapes = []
        for shape_id in range(reader.count("shape", _SHAPE_ROW_SIZE)):
            hi, lo = reader.unpack(">qq", "shape digest")
            kind = reader.unpack(">b", "shape kind")
            if not 0 <= kind < len(_KINDS):
                raise ShapesArtifactError(f"invalid shape kind {kind} in {path}")
            label_idx = reader.unpack(">i", "shape label index")
            if not 0 <= label_idx < len(labels):
                raise ShapesArtifactError(
                    f"shape #{shape_id} has invalid label index {label_idx} in {path}")
            count, shallow_total, shallow_min, shallow_max, roots = reader.unpack(
                ">qqqqq", "shape aggregates")
            scc_size, internal_edges, per_member = reader.unpack(
                ">iib", "shape SCC metadata")
            if (count < 0 or roots < 0 or shallow_total < 0 or shallow_min < 0
                    or shallow_max < shallow_min or scc_size <= 0 or internal_edges < 0):
                raise ShapesArtifactError(f"invalid aggregates for shape #{shape_id} in {path}")
            if per_member not in (0, 1):
                raise ShapesArtifactError(
                    f"invalid edgesPerMember value {per_member} for shape #{shape_id} in {path}")
            shapes.append({
                "id": shape_id,
                "digest": ((hi & 0xFFFFFFFFFFFFFFFF).to_bytes(8, "big").hex()
                           + (lo & 0xFFFFFFFFFFFFFFFF).to_bytes(8, "big").hex()),
                "kind": _KINDS[kind],
                "label": labels[label_idx],
                "count": count,
                "shallowTotal": shallow_total,
                "shallowMin": shallow_min,
                "shallowMax": shallow_max,
                "roots": roots,
                "sccSize": scc_size,
                "internalEdges": internal_edges,
                "edgesPerMember": bool(per_member),
            })

        edges = []
        for _ in range(reader.count("edge", _EDGE_ROW_SIZE)):
            source, target, mult = reader.unpack(">iiq", "edge")
            if not (0 <= source < len(shapes) and 0 <= target < len(shapes)) or mult < 0:
                raise ShapesArtifactError(
                    f"invalid edge {source} -> {target} ({mult}) in {path}")
            edges.append([source, target, mult])

        roots_histogram = []
        for _ in range(reader.count("roots histogram", _ROOT_ROW_SIZE)):
            shape_id, count = reader.unpack(">iq", "roots histogram row")
            if not 0 <= shape_id < len(shapes) or count < 0:
                raise ShapesArtifactError(f"invalid roots histogram row in {path}")
            roots_histogram.append([shape_id, count])
        roots_histogram.sort(key=lambda row: -row[1])

        metadata = None
        if reader.offset < reader.size:
            if reader.read(4, "metadata magic") != _METADATA_MAGIC:
                raise ShapesArtifactError(f"unknown SHP1 metadata in {path}")
            length = reader.unpack(">i", "metadata length")
            raw = reader.read(length, "metadata")
            if reader.offset != reader.size:
                raise ShapesArtifactError(f"trailing data after SHP1 metadata in {path}")
            try:
                metadata = json.loads(raw.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as exc:
                raise ShapesArtifactError(f"invalid SHP1 metadata JSON in {path}") from exc
            if not isinstance(metadata, dict):
                raise ShapesArtifactError(f"SHP1 metadata must be an object in {path}")

    collapsed_count = sum(shape["count"] for shape in shapes)
    concealed_count = _concealed_objects(metadata)
    return {
        "shapes": shapes,
        "edges": edges,
        "rootsHistogram": roots_histogram,
        "reachableCount": collapsed_count + concealed_count,
        "analysis": metadata,
    }


def _relaxation_counter(relaxation, name):
    value = relaxation.get(name, 0)
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise ShapesArtifactError(f"invalid {name} metadata")
    return value


def _concealed_objects(metadata):
    if not metadata:
        return 0
    relaxation = metadata.get("relaxation", {})
    if relaxation is None:
        return 0
    if not isinstance(relaxation, dict):
        raise ShapesArtifactError("SHP1 relaxation metadata must be an object")
    return _relaxation_counter(relaxation, "setChildrenConcealedObjects")


def validate_projection(path, metadata):
    """Check emitted aggregates against independent extraction statistics.

    Source refs and projected refs are deliberately different quantities.
    Relaxed classes conceal edges/members intentionally; their exact analyzer
    counters are included rather than treating the visible graph as raw heap.
    """
    payload = parse_shp1(path)
    rows = payload["shapes"]
    relaxation = metadata.get("relaxation") or {}
    if not isinstance(relaxation, dict):
        raise ShapesArtifactError("SHP1 relaxation metadata must be an object")
    concealed_objects = _concealed_objects(metadata)
    collapsed_count = sum(row["count"] for row in rows)
    totals = {
        "objects": collapsed_count + concealed_objects,
        "roots": sum(row[1] for row in payload["rootsHistogram"]),
        "shallowTotal": sum(row["shallowTotal"] for row in rows),
    }
    accounted = sum(
        row["internalEdges"] * (row["count"] // row["sccSize"]) for row in rows)
    for source, _target, mult in payload["edges"]:
        row = rows[source]
        instances = row["count"] if row["edgesPerMember"] else row["count"] // row["sccSize"]
        accounted += mult * instances
    concealed_edges = (_relaxation_counter(relaxation, "classOnlyHiddenEdges")
                       + _relaxation_counter(relaxation, "setChildrenHiddenEdges"))
    totals["projectedEdges"] = accounted + concealed_edges
    for key, actual in totals.items():
        if actual != metadata.get(key):
            raise ShapesArtifactError(
                f"shapes {key}: emitted {actual} != extracted {metadata.get(key)}")


def grph_stats(path):
    """Return ``(objects, references)`` without loading the GRPH body.

    GRPH header: magic i32, sourceN i64, class table, object count i32.  Each
    object row is 16 bytes plus four bytes per reference, so the reference
    count follows from the exact remaining file size.
    """
    with _Reader(path) as reader:
        if reader.unpack(">i", "GRPH magic") != 0x47525048:
            raise ShapesArtifactError(f"bad GRPH magic in {path}")
        source_objects = reader.unpack(">q", "GRPH source object count")
        if source_objects < 0:
            raise ShapesArtifactError(f"invalid GRPH source object count in {path}")
        for _ in range(reader.count("GRPH class", 4)):
            length = reader.unpack(">i", "GRPH class name length")
            reader.skip(length, "GRPH class name")
        objects = reader.unpack(">i", "GRPH object count")
        if objects < 0:
            raise ShapesArtifactError(f"invalid GRPH object count {objects} in {path}")
        body = reader.size - reader.offset
        rows_size = 16 * objects
        if body < rows_size or (body - rows_size) % 4:
            raise ShapesArtifactError(f"invalid GRPH body size in {path}")
        references = (body - rows_size) // 4
    return objects, references
