#!/usr/bin/env python3
"""Exact tuple-based counterfactuals on a shapes API JSON payload.

Usage: python3 audit_immutable_residual.py payload.json [report.json]
No dump access or production mutation. SCCs stay distinct opaque atoms.
"""
import collections
import json
import sys
from pathlib import Path

ENTRY = "com.google.common.collect.ImmutableMapEntry"
COLLISION_ENTRY = ENTRY + "$NonTerminalImmutableMapEntry"
ENTRY_ARRAYS = {ENTRY + "[]", "java.util.Map$Entry[]"}
BOUNDARY_CLASSES = {
    "org.gradle.api.attributes.Attribute",
    "org.gradle.internal.snapshot.impl.CoercingStringValueSnapshot",
    "org.gradle.internal.snapshot.impl.IsolatedImmutableManagedValue",
}


def audit(payload):
    rows = payload["shapes"]
    children = [[] for _ in rows]
    parents = [[] for _ in rows]
    pending = [0] * len(rows)
    for source, target, multiplicity in payload["edges"]:
        children[source].append((target, multiplicity))
        parents[target].append(source)
        pending[source] += 1
    queue = collections.deque(i for i, degree in enumerate(pending) if degree == 0)
    order = []
    while queue:
        child = queue.popleft()
        order.append(child)
        for parent in parents[child]:
            pending[parent] -= 1
            if pending[parent] == 0:
                queue.append(parent)
    if len(order) != len(rows):
        raise ValueError("payload is not a shape DAG")

    def quotient(normalize_entry=False, normalize_arrays=False, boundary_atoms=False):
        interned = {}
        mapping = [-1] * len(rows)
        for source in order:
            row = rows[source]
            label, kind = row["label"], row["kind"]
            if kind == "scc":
                key = ("opaque-scc", source)
            elif boundary_atoms and label in BOUNDARY_CLASSES:
                # Deliberate loss of these outgoing edges, ONLY a sensitivity
                # control. This does not establish which fields cross scope.
                key = ("boundary-sensitivity-atom", label)
            else:
                if normalize_entry and label == COLLISION_ENTRY:
                    label = ENTRY
                if normalize_arrays and label in ENTRY_ARRAYS:
                    label = "diagnostic:GuavaMapEntries[]"
                counts = collections.Counter()
                for child, multiplicity in children[source]:
                    counts[mapping[child]] += multiplicity
                key = (kind, label, tuple(sorted(counts.items())))
            mapping[source] = interned.setdefault(key, len(interned))
        by_label = collections.defaultdict(list)
        for source, row in enumerate(rows):
            by_label[row["label"]].append(source)
        return {
            "shapes": len(interned),
            "rootShapes": len({mapping[i] for i, _ in payload["rootsHistogram"]}),
            "byLabel": [
                {"label": label, "original": len(ids),
                 "normalized": len({mapping[i] for i in ids})}
                for label, ids in sorted(by_label.items(), key=lambda pair: -len(pair[1]))
            ],
        }

    modes = {
        "baseline": {},
        "entryClass": {"normalize_entry": True},
        "entryArrayClass": {"normalize_arrays": True},
        "entryAndArrayClass": {"normalize_entry": True, "normalize_arrays": True},
        "boundarySensitivity": {"boundary_atoms": True},
        "combinedSensitivity": {"normalize_entry": True, "normalize_arrays": True,
                                "boundary_atoms": True},
    }
    result = {name: quotient(**args) for name, args in modes.items()}
    if result["baseline"]["shapes"] != len(rows):
        raise ValueError("baseline did not reproduce original partition")
    direct = collections.defaultdict(list)
    for source, row in enumerate(rows):
        if row["label"] in (ENTRY, COLLISION_ENTRY):
            direct[(row["kind"], tuple(sorted(children[source])))].append(source)
    result["directCollisionSubclassWitnesses"] = [
        [{"id": i, "class": rows[i]["label"], "count": rows[i]["count"],
          "children": children[i]} for i in ids]
        for ids in direct.values() if len({rows[i]["label"] for i in ids}) > 1
    ]
    return result


if __name__ == "__main__":
    report = audit(json.loads(Path(sys.argv[1]).read_text()))
    for name, stats in report.items():
        if isinstance(stats, dict):
            print(name, stats["shapes"], "shapes;", stats["rootShapes"], "root shapes")
    print("direct collision-subclass witnesses:", len(report["directCollisionSubclassWitnesses"]))
    if len(sys.argv) > 2:
        Path(sys.argv[2]).write_text(json.dumps(report, indent=2))
