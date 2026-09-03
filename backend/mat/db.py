"""backend/mat/db — the per-dump analysis store: dumps/<id>/data/analysis.db.

MAT's report engine only outputs CSV, so CSV stays the landing/interchange
format (CI publishes it, MatRunner writes it) — but once a CSV lands it is
ingested here and DELETED. Resumability moves from "CSV exists" to "kv marker
row exists" (`part:<name>`); db commits are transactional, so the file's
presence implies completeness (localstore.files.has_data keys on it).

Raw tables hold exactly what today's CSVs held (keyed by extraction where
applicable); derived tables (reach/sgroups/slinks) are written by the
reachability pass (reach.py) at analyze time, and the served anatomy payload
is precomputed there too (the payloads table — a giant extraction rebuilt
from raw rows per HTTP request hung the server). Read helpers return the same
structures the old CSV parsers produced, so payloads.py is untouched.

SCHEMA_VERSION lives in the kv table; a mismatch wipes and recreates the
schema — the caller re-ingests / re-analyzes. No old-format compat (the
one-time migration is tools/migrate_analysis_db.py).
"""
from __future__ import annotations

import json
import os
import sqlite3

from .parsing import SKIP_FIELD, _parse_fields_dump, _read_csv

SCHEMA_VERSION = 2

SCHEMA = """
CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT);
CREATE TABLE hist (cls TEXT, n INT, s INT);
CREATE TABLE dom (cls TEXT, n INT, s INT, r INT);
CREATE TABLE classes (key TEXT PRIMARY KEY, cls TEXT);
CREATE TABLE rs (key TEXT, cls TEXT, n INT, s INT);
CREATE INDEX rs_key ON rs(key);
CREATE TABLE idsall (key TEXT, oid INT);
CREATE INDEX idsall_key ON idsall(key);
CREATE TABLE samples (key TEXT, k INT, ids_json TEXT, has_strings INT,
                      PRIMARY KEY (key, k));
CREATE TABLE nodes (key TEXT, k INT, oid INT, addr INT, cls TEXT,
                    used INT, ret INT, PRIMARY KEY (key, k, oid));
CREATE TABLE einfo (key TEXT, k INT, oid INT, elen INT,
                    PRIMARY KEY (key, k, oid));
-- edges/edgesfull are read "WHERE key=? AND k=? ORDER BY oid, slot" (anat_src):
-- the index must cover slot too, or SQLite sorts the whole extraction through
-- a temp b-tree per read (millions of rows on a big retained set)
CREATE TABLE edges (key TEXT, k INT, oid INT, slot INT, tid INT);
CREATE INDEX edges_key ON edges(key, k, oid, slot);
CREATE TABLE edgesfull (key TEXT, k INT, oid INT, slot INT, tid INT);
CREATE INDEX edgesfull_key ON edgesfull(key, k, oid, slot);
CREATE TABLE fields (key TEXT, k INT, oid INT, raw TEXT,
                     PRIMARY KEY (key, k, oid));
CREATE TABLE strings (key TEXT, k INT, addr INT, value TEXT,
                      PRIMARY KEY (key, k, addr));
CREATE TABLE reach (key TEXT, k INT, cls TEXT, rincl INT, rshared INT,
                    PRIMARY KEY (key, k, cls));
CREATE TABLE sgroups (key TEXT, k INT, gid INT, cls TEXT, holders_json TEXT,
                      n INT, s INT, r INT, rincl INT, rshared INT,
                      PRIMARY KEY (key, k, gid));
CREATE TABLE slinks (key TEXT, k INT, s INT, t INT, f TEXT, n INT, b INT);
CREATE INDEX slinks_key ON slinks(key, k);
CREATE TABLE payloads (key TEXT, k INT, kind TEXT, json TEXT,
                       PRIMARY KEY (key, k, kind));
"""

TABLES = ["kv", "hist", "dom", "classes", "rs", "idsall", "samples", "nodes",
          "einfo", "edges", "edgesfull", "fields", "strings", "reach",
          "sgroups", "slinks", "payloads"]


def db_path(data_dir):
    return os.path.join(data_dir, "analysis.db")


def fp_paths(data_dir):
    """Cache-fingerprint inputs: the db file plus its WAL companions (writes
    land in the -wal first, so the main file's mtime alone can go stale)."""
    p = db_path(data_dir)
    return [p, p + "-wal", p + "-shm"]


def open_db(data_dir):
    """Open (creating if needed) the dump's analysis.db with the current
    schema. A SCHEMA_VERSION mismatch wipes and recreates — the caller
    re-ingests / re-analyzes; there is no compat code by design."""
    os.makedirs(data_dir, exist_ok=True)
    db = sqlite3.connect(db_path(data_dir), timeout=30)
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("PRAGMA synchronous=NORMAL")
    have = None
    try:
        have = db.execute("SELECT v FROM kv WHERE k='schema'").fetchone()
    except sqlite3.OperationalError:
        pass   # no kv table yet — fresh db
    if have and have[0] == str(SCHEMA_VERSION):
        return db
    for t in TABLES:
        db.execute(f"DROP TABLE IF EXISTS {t}")
    db.executescript(SCHEMA)
    db.execute("INSERT INTO kv (k, v) VALUES ('schema', ?)",
               (str(SCHEMA_VERSION),))
    db.commit()
    return db


# ---------------------------------------------------------------- markers (resumability)

def has_marker(db, name):
    return db.execute("SELECT 1 FROM kv WHERE k=?", (name,)).fetchone() is not None


def _set_marker(db, name):
    db.execute("INSERT OR REPLACE INTO kv (k, v) VALUES (?, '1')", (name,))


# ---------------------------------------------------------------- ingest (CSV -> table -> delete CSV)

def ingest_hist(db, path):
    """histogram.csv -> hist (full replace). Returns row count; deletes the CSV."""
    rows = [(r[0], int(r[1]), int(r[2]))
            for r in _read_csv(path)
            if len(r) >= 3 and r[0] != "Class Name" and r[1].isdigit()]
    with db:
        db.execute("DELETE FROM hist")
        db.executemany("INSERT INTO hist (cls, n, s) VALUES (?,?,?)", rows)
        _set_marker(db, "part:hist")
    os.remove(path)
    return len(rows)


def ingest_dom(db, path):
    """dominator_by_class.csv -> dom (full replace). Deletes the CSV."""
    rows = [(r[0], int(r[1]), int(r[2]), int(r[3]))
            for r in _read_csv(path)
            if len(r) >= 5 and r[0] != "Class Name" and r[1].lstrip("-").isdigit()]
    with db:
        db.execute("DELETE FROM dom")
        db.executemany("INSERT INTO dom (cls, n, s, r) VALUES (?,?,?,?)", rows)
        _set_marker(db, "part:dom")
    os.remove(path)
    return len(rows)


def ingest_data_dir(data_dir, log=lambda m: None):
    """The data-bundle landing hook: ingest the two extracts whose CSVs are
    present (a new-format bundle ships analysis.db directly — then there is
    nothing to do). dominator_by_package.csv is never ingested: nothing reads
    it (package groupings are derived from the by-class rows)."""
    db = open_db(data_dir)
    try:
        for name, fn in (("histogram.csv", ingest_hist),
                         ("dominator_by_class.csv", ingest_dom)):
            p = os.path.join(data_dir, name)
            if os.path.exists(p):
                log(f"  ingested {name} -> analysis.db ({fn(db, p)} rows)")
    finally:
        db.close()


def upsert_class(db, key, cls):
    with db:
        db.execute("INSERT OR REPLACE INTO classes (key, cls) VALUES (?,?)",
                   (key, cls))


def ingest_rs(db, key, path):
    """rs_<key>.csv (retained-set histogram) -> rs. Deletes the CSV."""
    rows = [(key, r[0], int(r[1]), int(r[2]))
            for r in _read_csv(path)[1:]
            if len(r) >= 3 and r[1].isdigit()]
    with db:
        db.execute("DELETE FROM rs WHERE key=?", (key,))
        db.executemany("INSERT INTO rs (key, cls, n, s) VALUES (?,?,?,?)", rows)
        _set_marker(db, f"part:rs:{key}")
    os.remove(path)
    return len(rows)


def ingest_idsall(db, key, path):
    """idsall_<key>.csv (full instance-id list) -> idsall. Deletes the CSV."""
    rows = [(key, int(r[0])) for r in _read_csv(path)[1:] if r and r[0].isdigit()]
    with db:
        db.execute("DELETE FROM idsall WHERE key=?", (key,))
        db.executemany("INSERT INTO idsall (key, oid) VALUES (?,?)", rows)
        _set_marker(db, f"part:idsall:{key}")
    os.remove(path)
    return len(rows)


def anat_files(anat_dir, key, K):
    """part name -> path of one anatomy extraction's landed CSVs, or None when
    the nodes part (the only mandatory one) is missing. Honors both the
    suffixed (<key>_s<K>_*) and the legacy unsuffixed (<key>_*) names."""
    prefix = f"{key}_s{K}_"
    if not os.path.exists(os.path.join(anat_dir, f"{prefix}nodes.csv")):
        prefix = f"{key}_"   # legacy: the original 8-sample extraction
    paths = {part: os.path.join(anat_dir, f"{prefix}{part}.csv")
             for part in ("nodes", "edges", "edgesfull", "fields", "strings")}
    if not os.path.exists(paths["nodes"]):
        return None
    return paths


def ingest_anat(db, key, K, ids, paths):
    """One anatomy extraction's landed CSVs -> nodes/einfo/edges/edgesfull/
    fields/strings + the samples row (ids_json = the sampled root ids). Any
    part except nodes may be legitimately absent (an empty MAT result writes
    no CSV) — it simply lands no rows. Deletes every CSV it ingested."""
    nodes, einfo, edges, edgesfull, fields, strings = [], [], [], [], [], []
    for r in _read_csv(paths["nodes"])[1:]:
        if len(r) >= 5 and r[0].isdigit():
            addr = int(r[1], 16) if r[1].startswith("0x") else 0
            nodes.append((key, K, int(r[0]), addr, r[2], int(r[3]), int(r[4])))
    p = paths["edges"]
    if os.path.exists(p):
        for r in _read_csv(p)[1:]:
            if len(r) >= 2 and r[0].isdigit():
                oid = int(r[0])
                einfo.append((key, K, oid, int(r[1]) if r[1].isdigit() else 0))
                edges.extend((key, K, oid, i, int(x))
                             for i, x in enumerate(r[2:]) if x.isdigit())
    p = paths["edgesfull"]
    if os.path.exists(p):
        for r in _read_csv(p)[1:]:
            if len(r) >= 2 and r[0].isdigit():
                edgesfull.extend((key, K, int(r[0]), i, int(x))
                                 for i, x in enumerate(r[2:]) if x.isdigit())
    p = paths["fields"]
    if os.path.exists(p):
        for r in _read_csv(p)[1:]:
            if len(r) >= 2 and r[0].isdigit():
                fields.append((key, K, int(r[0]), r[1]))
    p = paths["strings"]
    has_strings = os.path.exists(p)
    if has_strings:
        for r in _read_csv(p)[1:]:
            if len(r) >= 2 and r[0].startswith("0x"):
                v = r[1].replace("\n", " ").replace("\r", " ")
                strings.append((key, K, int(r[0], 16),
                                v[:60] + ("…" if len(v) > 60 else "")))
    with db:
        for t in ("nodes", "einfo", "edges", "edgesfull", "fields", "strings"):
            db.execute(f"DELETE FROM {t} WHERE key=? AND k=?", (key, K))
        db.executemany("INSERT INTO nodes VALUES (?,?,?,?,?,?,?)", nodes)
        db.executemany("INSERT INTO einfo VALUES (?,?,?,?)", einfo)
        db.executemany("INSERT INTO edges VALUES (?,?,?,?,?)", edges)
        db.executemany("INSERT INTO edgesfull VALUES (?,?,?,?,?)", edgesfull)
        db.executemany("INSERT INTO fields VALUES (?,?,?,?)", fields)
        db.executemany("INSERT INTO strings VALUES (?,?,?,?)", strings)
        db.execute(
            "INSERT OR REPLACE INTO samples (key, k, ids_json, has_strings)"
            " VALUES (?,?,?,?)", (key, K, json.dumps([int(x) for x in ids]),
                                  1 if has_strings else 0))
        _set_marker(db, f"part:anat:{key}:{K}")
    for p in paths.values():
        if os.path.exists(p):
            os.remove(p)
    return len(nodes)


def write_reach(db, key, K, reach_rows, sgroup_rows, slink_rows):
    """Derived tables for one extraction (delete + insert — idempotent).
    reach_rows: (cls, rincl, rshared); sgroup_rows: (gid, cls, holders_json,
    n, s, r, rincl, rshared); slink_rows: (s_gid, t_gid, field, n, bytes)."""
    with db:
        for t in ("reach", "sgroups", "slinks"):
            db.execute(f"DELETE FROM {t} WHERE key=? AND k=?", (key, K))
        db.executemany("INSERT INTO reach VALUES (?,?,?,?,?)",
                       [(key, K, c, ri, rs) for c, ri, rs in reach_rows])
        db.executemany("INSERT INTO sgroups VALUES (?,?,?,?,?,?,?,?,?,?)",
                       [(key, K, *row) for row in sgroup_rows])
        db.executemany("INSERT INTO slinks VALUES (?,?,?,?,?,?,?)",
                       [(key, K, *row) for row in slink_rows])
        _set_marker(db, f"part:reach:{key}:{K}")


def write_payload(db, key, K, kind, payload):
    """Precomputed query payload for one extraction (kind "anat"), built once
    at analyze time from the same in-memory src the reach pass consumed and
    served as a blob — rebuilding it from raw rows per request is what made
    huge extractions hang the server. `available` is injected at serve time,
    so a later sample escalation never stales the stored blob."""
    with db:
        db.execute("INSERT OR REPLACE INTO payloads VALUES (?,?,?,?)",
                   (key, K, kind, json.dumps(payload)))


def read_payload(db, key, K, kind):
    """The stored payload JSON text, or None (extraction predates the
    precompute / the job died between the reach pass and the write)."""
    row = db.execute("SELECT json FROM payloads WHERE key=? AND k=? AND kind=?",
                     (key, K, kind)).fetchone()
    return row[0] if row else None


def split_from_rows(sgroup_rows, slink_rows):
    """(gid, cls, holders_json, n, s, r, rincl, rshared) + (s, t, f, n, b) rows
    -> the payload's split dict ({nodes, links}; gid ordering is the node
    index). Shared by anat_src (db read) and the analyze-time precompute,
    which already holds these rows in memory."""
    gid2i = {g[0]: i for i, g in enumerate(sgroup_rows)}
    snodes = [[g[1], g[3], g[4], g[5], g[6], g[7],
               json.loads(g[2]) if g[2] is not None else None]
              for g in sgroup_rows]
    links = [[gid2i[s], gid2i[t], f, n, b] for s, t, f, n, b in slink_rows
             if s in gid2i and t in gid2i]
    return {"nodes": snodes, "links": links}


# ---------------------------------------------------------------- readers
# Same structures the old CSV parsers produced — payloads.py is untouched.

def read_hist(db):
    """[(name, objects, shallow)] in landing order."""
    return [(c, n, s) for c, n, s in
            db.execute("SELECT cls, n, s FROM hist ORDER BY rowid")]


def read_dom(db):
    """[(name, objects, shallow, retained)] in landing order."""
    return [(c, n, s, r) for c, n, s, r in
            db.execute("SELECT cls, n, s, r FROM dom ORDER BY rowid")]


def known_classes(db):
    """{key: full class name} — the analysis index's key space."""
    return {k: c for k, c in db.execute("SELECT key, cls FROM classes")}


def read_analysis_index(db):
    """full class name -> {key, comp: bool, anat: [sample counts available]}"""
    comp = {r[0] for r in db.execute("SELECT DISTINCT key FROM rs")}
    anat = {}
    for key, k in db.execute("SELECT key, k FROM samples"):
        anat.setdefault(key, set()).add(k)
    return {cls: {"key": key, "comp": key in comp,
                  "anat": sorted(anat.get(key, []))}
            for key, cls in db.execute("SELECT key, cls FROM classes")}


def read_rs_totals(db):
    """key -> (retained shallow total, retained object count, #classes in set).
    The rs rows are the retained-set histogram and include the class itself."""
    return {key: (ts, tc, n) for key, ts, tc, n in db.execute(
        "SELECT key, SUM(s), SUM(n), COUNT(*) FROM rs GROUP BY key")}


def read_rs_rows(db, key):
    """[(cls, n, s)] — the retained-set histogram rows of one class."""
    return [(c, n, s) for c, n, s in
            db.execute("SELECT cls, n, s FROM rs WHERE key=? ORDER BY rowid",
                       (key,))]


def read_idsall(db, key):
    """[oid] — the full instance-id list (sample escalation re-reads it)."""
    return [r[0] for r in
            db.execute("SELECT oid FROM idsall WHERE key=? ORDER BY rowid",
                       (key,))]


def list_anat(db):
    """[(key, K)] — every anatomy extraction present (migration backfill)."""
    return [(k, kk) for k, kk in
            db.execute("SELECT key, k FROM samples ORDER BY key, k")]


def anat_src(db, key, K):
    """One anatomy extraction -> the src dict the tree builder consumes (same
    shape as the old CSV loader), extended with `reach` ({cls: (rincl,
    rshared)}) and `split` ({nodes, links}) from the derived tables — both
    None when the reachability pass has not run for this extraction."""
    row = db.execute("SELECT ids_json FROM samples WHERE key=? AND k=?",
                     (key, K)).fetchone()
    if row is None:
        return None
    nodes, addr2id = {}, {}
    for oid, addr, cls, used, ret in db.execute(
            "SELECT oid, addr, cls, used, ret FROM nodes WHERE key=? AND k=?",
            (key, K)):
        nodes[oid] = {"addr": addr, "cls": cls, "used": used, "ret": ret}
        addr2id[addr] = oid
    # refs/prims keep the SKIP_FIELD flag instead of dropping the fields:
    # synthetic fields (this$0 etc.) are real holders and get traversed
    refs, prims = {}, {}
    for oid, raw in db.execute(
            "SELECT oid, raw FROM fields WHERE key=? AND k=?", (key, K)):
        for typ, name, val in _parse_fields_dump(raw):
            sk = bool(SKIP_FIELD.match(name))
            if typ.startswith("ref"):
                if val.startswith("0x"):
                    refs.setdefault(oid, []).append((name, int(val, 16), sk))
            else:
                prims.setdefault(oid, []).append((name, val, sk))
    edges, edges_full, elen = {}, {}, {}
    for oid, e in db.execute(
            "SELECT oid, elen FROM einfo WHERE key=? AND k=?", (key, K)):
        elen[oid] = e
    for oid, slot, tid in db.execute(
            "SELECT oid, slot, tid FROM edges WHERE key=? AND k=?"
            " ORDER BY oid, slot", (key, K)):
        edges.setdefault(oid, []).append(tid)
    for oid, slot, tid in db.execute(
            "SELECT oid, slot, tid FROM edgesfull WHERE key=? AND k=?"
            " ORDER BY oid, slot", (key, K)):
        edges_full.setdefault(oid, []).append(tid)
    strings = {addr: v for addr, v in db.execute(
        "SELECT addr, value FROM strings WHERE key=? AND k=?", (key, K))}
    reach_rows = list(db.execute(
        "SELECT cls, rincl, rshared FROM reach WHERE key=? AND k=?", (key, K)))
    reach = {c: (ri, rs) for c, ri, rs in reach_rows} if reach_rows else None
    split = None
    grows = list(db.execute(
        "SELECT gid, cls, holders_json, n, s, r, rincl, rshared FROM sgroups"
        " WHERE key=? AND k=? ORDER BY gid", (key, K)))
    if grows:
        split = split_from_rows(grows, list(db.execute(
            "SELECT s, t, f, n, b FROM slinks WHERE key=? AND k=? ORDER BY rowid",
            (key, K))))
    return {"nodes": nodes, "addr2id": addr2id, "refs": refs, "prims": prims,
            "edges": edges, "edgesFull": edges_full, "elen": elen,
            "hasFullEdges": bool(edges_full), "strings": strings,
            "ids": [int(x) for x in json.loads(row[0])],
            "reach": reach, "split": split}
