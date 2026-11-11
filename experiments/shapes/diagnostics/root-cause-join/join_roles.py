#!/usr/bin/env python3
"""Root-cause join for the normalized retained shapes (run-jfr-38).

Inputs (see README.md for how each is produced):
  --module-sigs   module-signatures.tsv  (from module_signatures.py)
  --root-shapes   normalized-root-population.tsv  (dense<TAB>shape per root, dense ascending)
  --root-names    root-names.tsv  (mat_id<TAB>name<flags>, mat_id ascending)
  --root-paths    root-paths.tsv  (mat_id<TAB>identityPath, mat_id ascending)

Verifies the row-order join (dense order == sorted mat_id order over the
327,035 queried roots), then reports, per configuration role:
  * whether (tier, ndirect, closureSize) functionally determines the shape
    for all non-special modules (bad sigs = sigs mapping to >1 shape),
  * the exact per-role decomposition into content / IDE-model / HTTP / app,
  * the deviating modules per role.
"""
import argparse, collections, csv, sys

SPECIAL = {
    # IDE-model built modules (description sharing, artifact 1)
    ":api:f0-api", ":common:common-0", ":core:core-1",
    ":impl:f0-impl", ":ui:f0-ui", ":foundation:foundation-0",
    # cold-cache HTTP only (artifact 2)
    ":impl:f1-impl", ":impl:f2-impl", ":impl:f3-impl", ":impl:f4-impl",
    ":app:app",
}
DEV6 = {":api:f0-api", ":common:common-0", ":core:core-1",
        ":impl:f0-impl", ":ui:f0-ui", ":foundation:foundation-0"}
F4 = {":impl:f1-impl", ":impl:f2-impl", ":impl:f3-impl", ":impl:f4-impl"}


def load_tsv(p):
    with open(p) as f:
        return list(csv.DictReader(f, delimiter="\t"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--module-sigs", required=True)
    ap.add_argument("--root-shapes", required=True)
    ap.add_argument("--root-names", required=True)
    ap.add_argument("--root-paths", required=True)
    a = ap.parse_args()

    sig = {}
    for r in load_tsv(a.module_sigs):
        sig[r["module"]] = (r["tier"], int(r["ndirect"]), int(r["closure"]))

    shapes = load_tsv(a.root_shapes)
    names = load_tsv(a.root_names)
    paths = load_tsv(a.root_paths)
    assert len(shapes) == len(names) == len(paths), "row count mismatch"
    assert all(int(shapes[i]["dense"]) <= int(shapes[i + 1]["dense"]) for i in range(len(shapes) - 1))
    assert all(int(names[i]["mat_id"]) < int(names[i + 1]["mat_id"]) for i in range(len(names) - 1))
    assert names[0]["mat_id"] == paths[0]["mat_id"] and names[-1]["mat_id"] == paths[-1]["mat_id"]

    rows = []
    for i in range(len(shapes)):
        p = paths[i]["identityPath"]
        modpath, _, cfgname = p.rpartition(":")
        assert cfgname == names[i]["name"], (i, cfgname, names[i]["name"])
        rows.append((cfgname, modpath or "<root>", int(shapes[i]["shape"])))

    byrole = collections.defaultdict(list)
    for n, m, s in rows:
        byrole[n].append((m, s))

    total_bad = 0
    print(f"{'variants':>8} {'content':>7} {'IDE':>4} {'HTTP':>4} {'app':>4} {'badSigs':>7}  role")
    for role in sorted(byrole, key=lambda r: -len({s for _, s in byrole[r]})):
        rs = byrole[role]
        plain = [(m, s) for m, s in rs if m in sig and m not in SPECIAL]
        sig2s = collections.defaultdict(set)
        for m, s in plain:
            sig2s[sig[m]].add(s)
        bad = {k: v for k, v in sig2s.items() if len(v) > 1}
        content = set().union(*sig2s.values()) if sig2s else set()
        dev6 = {s for m, s in rs if m in DEV6} - content
        f4 = {s for m, s in rs if m in F4} - content
        app = {s for m, s in rs if m == ":app:app"} - content
        tot = len({s for _, s in rs})
        total_bad += len(bad)
        print(f"{tot:8d} {len(content):7d} {len(dev6):4d} {len(f4):4d} {len(app):4d} {len(bad):7d}  {role}")
        for k, v in sorted(bad.items()):
            minority = [m for m, s in plain if sig[m] == k]
            print(f"        BAD SIG {k} -> {sorted(v)} modules={minority[:6]}")
    print(f"\ntotal ambiguous (tier,ndirect,closure) sigs across all roles: {total_bad}")
    print("(0 = the shape of every non-special module is functionally determined")
    print(" by tier template, declared dependency count, and closure size)")


if __name__ == "__main__":
    main()
