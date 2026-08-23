#!/usr/bin/env python3
"""tools/migrate_analysis_db.py — ONE-TIME migration (throwaway, LAYOUT.md):
CSV extracts + sidecars -> per-dump data/analysis.db.

Walks the dumps root (or --dump <id>): ingests histogram/dominator CSVs,
rs_*/idsall_* CSVs and anat/* extractions (CSV + <key>_s<K>.json sidecars)
via the shared ingest path (backend/mat/db.py), then runs the reachability
pass (backend/mat/reach.py) for every (key, K) — the slow part, minutes for
big extractions, logged — and finally strips the migrated analysis-index
fields (classes/rs/anatSamples/ids) from meta.json. Unknown files (ad-hoc
manual extracts) are left alone. Run once, with the server stopped:

    python3 tools/migrate_analysis_db.py [--dumps DIR] [--dump <id>]
"""
import argparse
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from backend.mat import db as dbmod          # noqa: E402
from backend.mat import reach as reachmod    # noqa: E402


def _load_meta(data):
    try:
        with open(os.path.join(data, "meta.json")) as f:
            m = json.load(f)
        return m if isinstance(m, dict) else {}
    except Exception:
        return {}


def _anat_ks(anatdir, key, meta):
    """Sample counts on disk for one class: <key>_s<K>_nodes.csv, plus the
    legacy unsuffixed <key>_nodes.csv (= the original 8-sample extraction)."""
    ks = set(meta.get("anatSamples", {}).get(key, []))
    if os.path.isdir(anatdir):
        for fn in os.listdir(anatdir):
            m = re.match(re.escape(key) + r"_s(\d+)_nodes\.csv$", fn)
            if m:
                ks.add(int(m.group(1)))
            elif fn == f"{key}_nodes.csv":
                ks.add(8)
    return sorted(ks)


def _sidecar_ids(anatdir, key, K, meta):
    p = os.path.join(anatdir, f"{key}_s{K}.json")
    try:
        with open(p) as f:
            ids = json.load(f).get("ids")
            if ids:
                return [int(x) for x in ids], p
    except Exception:
        pass
    ids = meta.get("ids", {}).get(key, [])
    return ([int(x) for x in ids] if ids else []), p


def migrate_dump(dump_dir, log=print):
    data = os.path.join(dump_dir, "data")
    if not os.path.isdir(data):
        return False
    dump_id = os.path.basename(dump_dir)
    meta = _load_meta(data)
    classes = meta.get("classes", {})
    if not classes and not os.path.exists(os.path.join(data, "histogram.csv")) \
            and not os.path.exists(dbmod.db_path(data)):
        return False   # nothing to migrate (never create an empty db)
    db = dbmod.open_db(data)
    try:
        dbmod.ingest_data_dir(data, log=lambda m: log(f"  {dump_id}: {m}"))
        for key, cls in classes.items():
            dbmod.upsert_class(db, key, cls)
            rs_name = meta.get("rs", {}).get(key, f"rs_{key}.csv")
            p = os.path.join(data, rs_name)
            if os.path.exists(p):
                n = dbmod.ingest_rs(db, key, p)
                log(f"  {dump_id}: ingested {rs_name} ({n} rows)")
            p = os.path.join(data, f"idsall_{key}.csv")
            if os.path.exists(p):
                n = dbmod.ingest_idsall(db, key, p)
                log(f"  {dump_id}: ingested idsall_{key}.csv ({n} ids)")
        anatdir = os.path.join(data, "anat")
        for key in classes:
            for K in _anat_ks(anatdir, key, meta):
                if dbmod.has_marker(db, f"part:anat:{key}:{K}"):
                    continue
                paths = dbmod.anat_files(anatdir, key, K)
                if paths is None:
                    continue
                ids, side = _sidecar_ids(anatdir, key, K, meta)
                if not ids:
                    log(f"  {dump_id}: WARNING no sampled ids for {key} s{K} "
                        "— ingesting without reach")
                n = dbmod.ingest_anat(db, key, K, ids or [0], paths)
                if os.path.exists(side):
                    os.remove(side)
                log(f"  {dump_id}: ingested anat {key} s{K} ({n} objects)")
        # reach pass for every extraction that lacks it
        for key, K in dbmod.list_anat(db):
            if dbmod.has_marker(db, f"part:reach:{key}:{K}"):
                continue
            src = dbmod.anat_src(db, key, K)
            if not src["ids"] or src["ids"] == [0]:
                log(f"  {dump_id}: SKIP reach {key} s{K} (no sampled ids)")
                continue
            log(f"  {dump_id}: reach {key} s{K} "
                f"({len(src['nodes'])} objects) ...")
            rows = reachmod.compute(src, src["ids"], log)
            dbmod.write_reach(db, key, K, *rows)
        # verify: ingested extracts are readable back
        n_hist = db.execute("SELECT COUNT(*) FROM hist").fetchone()[0]
        n_dom = db.execute("SELECT COUNT(*) FROM dom").fetchone()[0]
        n_anat = len(dbmod.list_anat(db))
        log(f"  {dump_id}: analysis.db — hist={n_hist} dom={n_dom} "
            f"extractions={n_anat}")
    finally:
        db.close()
    # strip the migrated analysis-index fields (the db owns them now)
    leftover = {k: meta.pop(k) for k in ("classes", "rs", "anatSamples", "ids")
                if k in meta}
    if leftover:
        tmp = os.path.join(data, f".meta.json.tmp{os.getpid()}")
        with open(tmp, "w") as f:
            json.dump(meta, f, indent=1)
        os.replace(tmp, os.path.join(data, "meta.json"))
    if os.path.isdir(anatdir) and not os.listdir(anatdir):
        os.rmdir(anatdir)
    return True


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--dumps", default=os.environ.get(
        "HEAP_REPORT_DUMPS", os.path.join(os.path.dirname(
            os.path.dirname(os.path.abspath(__file__))), "dumps")))
    p.add_argument("--dump", help="migrate just this dump id")
    args = p.parse_args(argv)
    ids = [args.dump] if args.dump else sorted(
        d for d in os.listdir(args.dumps)
        if os.path.isdir(os.path.join(args.dumps, d)))
    done = 0
    for dump_id in ids:
        if migrate_dump(os.path.join(args.dumps, dump_id)):
            done += 1
    print(f"migrated {done}/{len(ids)} dumps")
    return 0


if __name__ == "__main__":
    sys.exit(main())
