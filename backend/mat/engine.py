"""backend/mat/engine — MatQueryEngine: the MAT-backed core.QueryEngine +
local bootstrap (LocalIndexer role).

  - Payload caches live here and only here, keyed on analysis.db's mtime AND
    meta's state; invalidate(dump_id) drops everything cached for a dump. The
    store/kernel calls invalidate() when a dump's data changes (download,
    bootstrap, compact, finished analysis). Concurrent loads of the same key
    are deduped (_loading): followers wait for the in-flight build.
  - The anatomy payload is PRECOMPUTED at analyze time and stored as a JSON
    blob in analysis.db's payloads table (db.write_payload) — anatomy() is a
    blob read; rebuilding from raw rows per request made huge extractions
    hang the server.
  - No on-disk payload caches: only the store writes a dump dir. The in-memory
    payload cache over the db covers the re-read cost.
  - Extracts land as CSV (MAT's only output), are ingested into the per-dump
    data/analysis.db (db.py) and deleted; resumability is the db's kv marker
    rows. A failed extraction raises -> the ANALYZE job is FAILED with the
    real error and nothing is recorded for data that does not exist.
  - Overview queries (trees/classes/compare) only need the tiny data bundle,
    so they are served from any busy state once it is ingested (_data_early);
    analysis-level queries stay READY-only (_data_dir).

All dump-dir paths come from store.dir_of (state gating lives here); all
meta.json writes go through store.update_meta (single writer). Every MAT
subprocess goes through extract.MatRunner.
"""
from __future__ import annotations

import glob
import json
import os
import re
import threading

from .. import core, machine
from ..localstore import drop_index_set, raws_zsts
from . import db as dbmod
from . import reach as reachmod
from .parsing import _merge_fams, _read_csv
from .payloads import (PROXIES, _anatomy_build, _anatomy_diff,
                       _class_table_build, _composition_build, _stats_build,
                       _trees_build, _waterfall)
from .extract import (EDGE_FULL_CAP, IDS_LIMIT, MAT_JOBS, MAX_EDGE,
                      MAX_STRINGS, SAMPLES, CorruptIndexError, MatRunner, _par,
                      sample_even, subselect, suffix)

PAGE = 200                    # classes() page size
ANAT_DEPTH, ANAT_KIDS = 32, 40   # anatomy tree walk/fold parameters


class MatQueryEngine:
    """core.QueryEngine over the local dump store, plus the local MAT bootstrap
    (LocalIndexer role). All dump-dir paths come from store.dir_of (state
    gating lives in _data_dir/_data_early); all meta.json writes go through
    store.update_meta (single writer)."""

    def __init__(self, store: core.LocalDumpStore, jobs: core.JobRegistry):
        self._store = store
        self._jobs = jobs
        self._runner = MatRunner(jobs)
        self._lock = threading.RLock()
        self._cache = {}            # key tuple -> (fingerprint, payload)
        self._loading = {}          # key tuple -> Event: a _cached load in flight
        # the store stays free of any mat import: it calls this hook blind
        # when data-bundle files land (download stage) so they get ingested
        store.on_data_files = self._on_data_files

    # ---------------------------------------------------------- plumbing

    def _on_data_files(self, dump_id):
        """Store hook: data-bundle files just landed in data/ (or survived a
        crash between move and ingest) — ingest the CSVs into analysis.db and
        delete them."""
        data = os.path.join(self._store.dir_of(dump_id), "data")
        dbmod.ingest_data_dir(data)
        self.invalidate(dump_id)

    def _read_db(self, data, fn):
        db = dbmod.open_db(data)
        try:
            return fn(db)
        finally:
            db.close()

    def _data_dir(self, dump_id):
        """Analysis-level entry: READY only (dir_of hands out the dir in any
        state — gating lives here)."""
        info = self._store.get(dump_id)
        if info.state is not core.DumpState.READY:
            raise core.ApiError("bad_state",
                                f"dump {dump_id} is {info.state.value}, queries need ready", 409)
        return os.path.join(self._store.dir_of(dump_id), "data")

    def _data_early(self, dump_id):
        """Overview entry (trees/classes/compare): the tiny data bundle is
        enough, so these are served the moment it is unpacked — while the
        heavy hprof/index download or local indexing continues."""
        info = self._store.get(dump_id)
        if info.state in (core.DumpState.REMOTE, core.DumpState.FAILED):
            raise core.ApiError("bad_state",
                                f"dump {dump_id} is {info.state.value}", 409)
        data = os.path.join(self._store.dir_of(dump_id), "data")
        if not os.path.exists(dbmod.db_path(data)):
            raise core.ApiError(
                "bad_state", f"dump {dump_id} has no data bundle yet "
                f"({info.state.value}) — the overview appears once it lands", 409)
        return data

    def _meta(self, dump_id):
        return self._store.read_meta(dump_id)

    def _fp(self, dump_id, paths):
        """Cache fingerprint: newest mtime of the underlying files + meta's state."""
        mt = 0
        for p in paths:
            try:
                mt = max(mt, os.stat(p).st_mtime_ns)
            except OSError:
                pass
        try:
            state = self._meta(dump_id).get("state", "")
        except Exception:   # noqa: BLE001 - a meta read hiccup must not break queries
            state = ""
        return (mt, state)

    def _cached(self, key, fp, loader):
        """fp-fresh cache with in-flight dedupe: concurrent callers for the
        same key wait for the running load instead of each building a copy
        (concurrent giant builds multiplied the server's memory footprint)."""
        with self._lock:
            ent = self._cache.get(key)
            if ent is not None and ent[0] == fp:
                return ent[1]
            ev = self._loading.get(key)
            if ev is None:
                ev = self._loading[key] = threading.Event()
                leader = True
            else:
                leader = False
        if not leader:
            ev.wait()
            # the leader stored its value (or failed, or fp moved on) — re-enter
            return self._cached(key, fp, loader)
        try:
            val = loader()
            with self._lock:
                self._cache[key] = (fp, val)
            return val
        finally:
            with self._lock:
                del self._loading[key]
                ev.set()

    def invalidate(self, dump_id=None):
        """Drop cached payloads for one dump (or all). The store/kernel calls this
        when a dump's data changes: download, bootstrap, compact, finished analysis.
        The engine also calls it itself after a successful analyze/bootstrap job."""
        with self._lock:
            if dump_id is None:
                self._cache.clear()
            else:
                for k in [k for k in self._cache if dump_id in k]:
                    del self._cache[k]

    # ---------------------------------------------------------- cached raw loaders

    def _db_fp(self, dump_id):
        """The cache fingerprint of every db-backed entry: the db files' newest
        mtime + meta's state."""
        data = os.path.join(self._store.dir_of(dump_id), "data")
        return self._fp(dump_id, dbmod.fp_paths(data))

    def _load_hist(self, dump_id):
        data = self._data_early(dump_id)
        return self._cached((dump_id, "hist"), self._db_fp(dump_id),
                            lambda: self._read_db(data, dbmod.read_hist))

    def _load_dom(self, dump_id):
        data = self._data_early(dump_id)
        return self._cached((dump_id, "dom"), self._db_fp(dump_id),
                            lambda: self._read_db(data, dbmod.read_dom))

    def _analysis_index(self, dump_id):
        data = self._data_early(dump_id)
        return self._cached((dump_id, "anaidx"), self._db_fp(dump_id),
                            lambda: self._read_db(data, dbmod.read_analysis_index))

    def _rs_totals(self, dump_id):
        """full class -> (retained shallow total, retained object count,
        #classes in set), for analyzed (comp) classes only."""
        data = self._data_early(dump_id)

        def load():
            by_key = self._read_db(data, dbmod.read_rs_totals)
            idx = self._analysis_index(dump_id)
            return {full: by_key[st["key"]] for full, st in idx.items()
                    if st["comp"] and st["key"] in by_key}

        return self._cached((dump_id, "rstot"), self._db_fp(dump_id), load)

    # ---------------------------------------------------------- queries

    def trees(self, dump_id):
        self._data_early(dump_id)
        return self._cached(
            (dump_id, "trees"), self._db_fp(dump_id),
            lambda: {"stats": _stats_build(self._load_hist(dump_id), self._load_dom(dump_id),
                                           self._meta(dump_id), self._analysis_index(dump_id)),
                     "trees": _trees_build(self._load_hist(dump_id), self._load_dom(dump_id))})

    def _class_table(self, dump_id):
        self._data_early(dump_id)
        return self._cached((dump_id, "classtable"), self._db_fp(dump_id),
                            lambda: _class_table_build(self._load_hist(dump_id),
                                                       self._analysis_index(dump_id),
                                                       self._rs_totals(dump_id)))

    def classes(self, dump_id, filter="", sort="-s", page=0):
        rows = list(self._class_table(dump_id))   # copy: filter/sort must not touch the cache
        filt = filter.strip().lower()
        if filt:
            rows = [r for r in rows if filt in r["disp"].lower()]
        keymap = {
            "s": lambda r: r["s"], "c": lambda r: r["c"], "pi": lambda r: r["pi"],
            "r": lambda r: (r["r"] is not None, r["r"] or 0), "name": lambda r: r["disp"].lower(),
        }
        rev = sort.startswith("-")
        key = keymap.get(sort.lstrip("-"), keymap["s"])
        if sort.lstrip("-") == "r":   # unanalyzed (r=None) always last
            sign = -1 if rev else 1
            rows.sort(key=lambda r: (r["r"] is None, sign * (r["r"] or 0)))
        else:
            rows.sort(key=key, reverse=rev)
        page = max(0, page)
        total = len(rows)
        start = page * PAGE
        return {"rows": rows[start:start + PAGE], "total": total,
                "page": page, "pages": max(1, (total + PAGE - 1) // PAGE)}

    def composition(self, dump_id, cls):
        """Retained-set composition ("what's inside") for an analyzed class.
        None = not analyzed yet."""
        data = self._data_dir(dump_id)
        st = self._analysis_index(dump_id).get(cls)
        if not st or not st["comp"]:
            return None
        key = st["key"]
        return self._cached((dump_id, "comp", key), self._db_fp(dump_id),
                            lambda: _composition_build(
                                self._read_db(data, lambda db: dbmod.read_rs_rows(db, key))))

    def _anat_src(self, dump_id, key, K):
        data = self._data_dir(dump_id)
        return self._cached((dump_id, "anatsrc", key, K), self._db_fp(dump_id),
                            lambda: self._read_db(
                                data, lambda db: dbmod.anat_src(db, key, K)))

    def anatomy(self, dump_id, cls, samples=None):
        """Full-graph reference tree for one analyzed class. None = not analyzed.
        Served from the payload blob precomputed at analyze time (the analyze
        job already holds the extraction in memory for the reach pass); the
        on-demand rebuild from raw rows is only the fallback for an extraction
        whose job died between the reach pass and the payload write."""
        self._data_dir(dump_id)
        st = self._analysis_index(dump_id).get(cls)
        if not st or not st["anat"]:
            return None
        key = st["key"]
        avail = st["anat"]
        K = samples if samples in avail else avail[-1]
        data = os.path.join(self._store.dir_of(dump_id), "data")

        def load():
            raw = self._read_db(data, lambda db: dbmod.read_payload(db, key, K, "anat"))
            if raw is not None:
                pl = json.loads(raw)
                pl["available"] = avail   # fresh: escalations must not stale the blob
                return pl
            src = self._anat_src(dump_id, key, K)   # pre-blob extraction
            return None if src is None else _anatomy_build(src, cls, K, avail,
                                                           ANAT_DEPTH, ANAT_KIDS)

        return self._cached((dump_id, "anat", key, K), self._db_fp(dump_id), load)

    def compare(self, a, b):
        """Not payload-cached at this level: it assembles cheap merges over the
        per-dump caches, which carry freshness. Overview sections work off the
        data bundle alone (busy dumps allowed); retained/anatomy diffs need
        per-class analysis, so they only cover READY dumps."""
        da, db = self._data_early(a), self._data_early(b)
        A, B = _merge_fams(self._load_hist(a), 2), _merge_fams(self._load_hist(b), 2)
        rows = []
        for k in set(A) | set(B):
            co, so = A.get(k, (0, 0))
            cn, sn = B.get(k, (0, 0))
            rows.append([k, co, cn, cn - co, so, sn, sn - so])
        # top-level dominator deltas (owned memory — closer to cause than shallow histogram)
        DA = _merge_fams(self._load_dom(a), 3)
        DB = _merge_fams(self._load_dom(b), 3)
        dom = []
        for k in set(DA) | set(DB):
            oo, so, ro = DA.get(k, (0, 0, 0))
            on, sn, rn = DB.get(k, (0, 0, 0))
            dom.append([k, oo, on, so, sn, ro, rn, rn - ro])
        dom.sort(key=lambda r: -abs(r[7]))
        # retained deltas for classes analyzed in both dumps
        ta, tb = self._rs_totals(a), self._rs_totals(b)
        retained = []
        for full in sorted(set(ta) & set(tb)):
            retained.append([full, ta[full][0], tb[full][0], tb[full][0] - ta[full][0]])
        retained.sort(key=lambda r: -abs(r[3]))
        proxies = []
        for p in PROXIES:
            co, so = A.get(p, (0, 0))
            cn, sn = B.get(p, (0, 0))
            proxies.append([p, co, cn, so, sn])
        # composition availability per dump — drives the rs-diff drill-down buttons
        ia, ib = self._analysis_index(a), self._analysis_index(b)
        analyzed = {"old": sorted(f for f, st in ia.items() if st["comp"]),
                    "new": sorted(f for f, st in ib.items() if st["comp"])}
        # anatomy diffs for classes with an extraction in both dumps
        # (anatomy payloads stay READY-only — skip while either dump is busy)
        anats = {}
        both_ready = all(self._store.get(x).state is core.DumpState.READY
                         for x in (a, b))
        if both_ready:
            for full in sorted(set(ia) & set(ib)):
                if ia[full]["anat"] and ib[full]["anat"]:
                    d = _anatomy_diff(self.anatomy(a, full), self.anatomy(b, full))
                    if d:
                        anats[full] = d
        return {
            "old": _stats_build(self._load_hist(a), self._load_dom(a), self._meta(a), ia),
            "new": _stats_build(self._load_hist(b), self._load_dom(b), self._meta(b), ib),
            "rows": rows,
            "dom": dom,
            "waterfall": _waterfall(rows),
            "retained": retained,
            "proxies": proxies,
            "analyzed": analyzed,
            "anats": anats,
        }

    # ---------------------------------------------------------- analyze

    def _hprof(self, dump_id):
        """The hprof lives inside the dump dir, next to data/ and the MAT indexes."""
        dump_dir = self._store.dir_of(dump_id)
        meta = self._meta(dump_id)
        p = os.path.join(dump_dir, meta.get("dump", ""))
        if meta.get("dump") and os.path.exists(p):
            return p
        hits = glob.glob(os.path.join(dump_dir, "*.hprof"))
        return hits[0] if hits else None

    def _alloc_key(self, dump_id, full):
        """Stable short key for a class, collision-free against the db's
        classes table."""
        data = os.path.join(self._store.dir_of(dump_id), "data")
        classes = self._read_db(data, dbmod.known_classes)
        for k, v in classes.items():
            if v == full:
                return k
        base = re.sub(r"[^A-Za-z0-9_]", "_", full)[-60:] or "cls"
        key, i = base, 2
        while key in classes:
            key = f"{base}_{i}"
            i += 1
        return key

    def analyze(self, dump_id, cls, samples=SAMPLES, with_anatomy=True):
        """Queue on-demand per-class MAT analysis. The class is
        validated against the histogram first; a failed extraction surfaces as a
        FAILED job with the real error and is never recorded.

        Analysis needs the MAT index set, which READY dumps may not have yet.
        Index acquisition is the machine's job (store.request_indexes): the
        remote prebuilt set when some source has published it by now, else a
        local MAT parse — decided in ONE place (machine.decide), re-evaluated
        on every reconcile. This job just waits for the component to reach
        DONE. A corrupt compacted index (the restore deleted it) drops the
        untrusted set, hands the component back to the machine
        (note_indexes_corrupt) and waits again — remote re-download when
        published, local parse otherwise, no user round-trip."""
        try:
            samples = max(1, min(1024, int(samples)))
        except (TypeError, ValueError):
            samples = SAMPLES
        info = self._store.get(dump_id)
        if info.state is not core.DumpState.READY:
            raise core.ApiError("bad_state",
                                f"dump {dump_id} is {info.state.value} — analysis needs "
                                "ready (the overview works during the download)", 409)
        known = {name for name, _, _ in self._load_hist(dump_id)}
        if cls not in known:
            raise core.ApiError("not_found", f"class not in histogram: {cls}", 404)
        hprof = self._hprof(dump_id)
        if not hprof:
            raise core.ApiError("bad_state", "hprof not found in the dump dir", 400)
        self._store.request_indexes(dump_id)   # sticky: acquisition starts now,
        # overlaps the queue wait, and a mid-analysis corruption re-drives it

        def fn(job):
            if not self._has_indexes(dump_id):
                self._store.await_indexes(dump_id, job)
            try:
                self._analyze_class(job, dump_id, hprof, cls, samples, with_anatomy)
            except CorruptIndexError as e:
                self._jobs.log(job, f"  {e}")
                drop_index_set(self._store.dir_of(dump_id),
                               lambda m: self._jobs.log(job, m))
                self._store.note_indexes_corrupt(dump_id)
                self._store.await_indexes(dump_id, job)
                # the set is whole again — retry once; a second corruption
                # fails the job for real
                self._analyze_class(job, dump_id, hprof, cls, samples, with_anatomy)

        return self._jobs.submit(core.JobKind.ANALYZE, dump_id, cls, fn)

    def _has_indexes(self, dump_id):
        raws, zsts = raws_zsts(self._store.dir_of(dump_id))
        return bool(raws or zsts)

    def _analyze_class(self, job, dump_id, hprof, cls, samples, with_anatomy):
        """Retained-set composition + (optionally) reference-tree anatomy for one
        class. Resumable at the db-marker level: an ingested part is never
        re-run, so escalating to more samples only runs the missing
        extractions. Every MAT query still lands as CSV (MAT's only output)
        and is ingested into analysis.db + deleted right after; the reach
        pass (derived tables) and the served anatomy payload (payloads table)
        are computed once all parts of an extraction landed — from the same
        in-memory src, so the extraction is materialized once per analysis."""
        data = self._data_dir(dump_id)
        anat = os.path.join(data, "anat")
        os.makedirs(anat, exist_ok=True)
        key = self._alloc_key(dump_id, cls)
        log = lambda m: self._jobs.log(job, m)
        log(f"analyzing {cls} (key={key}, samples={samples}, anatomy={with_anatomy})")
        db = dbmod.open_db(data)
        try:
            # retained-set composition and the full id list are independent —
            # overlap them; ingested parts (marker row) are skipped entirely
            tasks = []
            if not dbmod.has_marker(db, f"part:rs:{key}"):
                tasks.append(lambda: self._runner.run(job, hprof, data, suffix("rs", key),
                                                      f"show_retained_set {cls}",
                                                      f"rs_{key}.csv"))
            if with_anatomy and not dbmod.has_marker(db, f"part:idsall:{key}"):
                tasks.append(lambda: self._runner.run(job, hprof, data, suffix("idsall", key),
                                                      f'oql "SELECT s.@objectId FROM INSTANCEOF {cls} s"',
                                                      f"idsall_{key}.csv", limit=IDS_LIMIT))
            if tasks:
                _par(tasks)
            p = os.path.join(data, f"rs_{key}.csv")
            if os.path.exists(p):
                dbmod.ingest_rs(db, key, p)
            if not dbmod.has_marker(db, f"part:rs:{key}"):
                raise RuntimeError("retained-set query failed")
            dbmod.upsert_class(db, key, cls)
            if not with_anatomy:
                self.invalidate(dump_id)
                return
            p = os.path.join(data, f"idsall_{key}.csv")
            if os.path.exists(p):
                dbmod.ingest_idsall(db, key, p)
            # full id list -> evenly-spaced sample of K instances
            picked = sample_even(dbmod.read_idsall(db, key), samples)
            if not picked:
                raise RuntimeError("no instance ids")
            K = len(picked)
            if not dbmod.has_marker(db, f"part:anat:{key}:{K}"):
                idx = ", ".join(f"outbounds(o)[{i}]" for i in range(MAX_EDGE))
                idx_full = ", ".join(f"outbounds(o)[{i}]" for i in range(EDGE_FULL_CAP))
                sub = subselect(cls, picked)
                # nodes / edges / fields scan the same retained set independently
                # — run concurrently. edgesfull captures complete outbounds for
                # objects with >MAX_EDGE refs (the plain edges query truncates at
                # MAX_EDGE, which silently orphans big-array children). Any of
                # these queries may legitimately return EMPTY (no sampled object
                # with >MAX_EDGE refs, no IInstance fields for array classes, ...):
                # MatRunner.run then writes no CSV and returns None, and the ingest
                # treats the absent file as "no data" — only nodes is mandatory.
                _par([
                    lambda: self._runner.run(job, hprof, anat, suffix("n2", f"{key}_s{K}"),
                            f'oql "SELECT o.@objectId, toHex(o.@objectAddress), classof(o).@name, o.@usedHeapSize, o.@retainedHeapSize FROM OBJECTS ({sub}) o"',
                            f"{key}_s{K}_nodes.csv"),
                    lambda: self._runner.run(job, hprof, anat, suffix("e2", f"{key}_s{K}"),
                            f'oql "SELECT o.@objectId, outbounds(o).length, {idx} FROM OBJECTS ({sub}) o"',
                            f"{key}_s{K}_edges.csv"),
                    lambda: self._runner.run(job, hprof, anat, suffix("f2", f"{key}_s{K}"),
                            f'oql "SELECT o.@objectId, o.getFields() FROM OBJECTS ({sub}) o WHERE o implements org.eclipse.mat.snapshot.model.IInstance"',
                            f"{key}_s{K}_fields.csv"),
                    lambda: self._runner.run(job, hprof, anat, suffix("ef", f"{key}_s{K}"),
                            f'oql "SELECT o.@objectId, outbounds(o).length, {idx_full} FROM OBJECTS ({sub}) o WHERE outbounds(o).length > {MAX_EDGE}"',
                            f"{key}_s{K}_edgesfull.csv"),
                ])
                nodes_p = os.path.join(anat, f"{key}_s{K}_nodes.csv")
                if not os.path.exists(nodes_p):
                    raise RuntimeError(f"anatomy extraction failed: {key}_s{K}_nodes.csv missing")
                strings_dst = os.path.join(anat, f"{key}_s{K}_strings.csv")
                if not os.path.exists(strings_dst):
                    addrs = [r[1] for r in _read_csv(nodes_p)[1:]
                             if len(r) >= 5 and r[2] == "java.lang.String"][:MAX_STRINGS]
                    # guarded: no String children -> the query would be a wasted MAT run
                    # (an empty result writes no CSV; missing strings.csv is tolerated)
                    if addrs:
                        self._runner.run(job, hprof, anat, suffix("s2", f"{key}_s{K}"),
                                f'oql "SELECT toHex(o.@objectAddress), toString(o) FROM OBJECTS {",".join(addrs)} o"',
                                f"{key}_s{K}_strings.csv")
                n = dbmod.ingest_anat(db, key, K, picked,
                                      dbmod.anat_files(anat, key, K))
                log(f"  anatomy ingested: {n} objects -> analysis.db")
            src = None
            if not dbmod.has_marker(db, f"part:reach:{key}:{K}"):
                src = dbmod.anat_src(db, key, K)
                log(f"  reach pass over {len(src['nodes'])} objects "
                    f"(inclusive retained + holder-set split) ...")
                rows = reachmod.compute(src, picked, log)
                dbmod.write_reach(db, key, K, *rows)
                # attach the just-written derived data so the payload build
                # below doesn't re-read the whole extraction from the db
                src["reach"] = {c: (ri, rs) for c, ri, rs in rows[0]}
                src["split"] = dbmod.split_from_rows(rows[1], rows[2])
            if dbmod.read_payload(db, key, K, "anat") is None:
                # the served anatomy payload is precomputed HERE (the job
                # already holds src; `available` is injected at serve time, so
                # a later sample escalation doesn't stale the blob). A job that
                # died between the reach pass and this write re-enters with the
                # marker set: src then comes from the db, derived data included.
                if src is None:
                    src = dbmod.anat_src(db, key, K)
                log(f"  precomputing the anatomy payload "
                    f"({len(src['nodes'])} objects) ...")
                dbmod.write_payload(db, key, K, "anat",
                                    _anatomy_build(src, cls, K, [],
                                                   ANAT_DEPTH, ANAT_KIDS))
            self.invalidate(dump_id)
            log(f"done: {cls} ({K} samples)")
        finally:
            db.close()

    # ---------------------------------------------------------- bootstrap (LocalIndexer)

    def parse_inline(self, dump_id, job):
        """The local MAT index parse, run INLINE in the caller's thread.
        await_indexes calls this on the serial MAT worker itself — queueing
        the parse as an INDEX job behind the waiting analyze job on the same
        single-threaded queue would deadlock. The store marks the parse live
        (rt.inline_indexes) so a late remote publication can still preempt it
        via the abort flag. Outcome is CAS-reported to the machine."""
        try:
            dump_dir = self._store.dir_of(dump_id)
            data = os.path.join(dump_dir, "data")
            hprof = self._hprof(dump_id)
            if not hprof:
                raise RuntimeError("no .hprof in the dump dir")
            self._jobs.log(job, f"local MAT parse of {hprof} (no published "
                                "indexes) — one-off, tens of minutes")
            # the first MAT query triggers the full parse; a histogram run is
            # that trigger, written under a throwaway name because the data
            # bundle's histogram.csv already exists and MatRunner.run
            # short-circuits on existing files
            p = self._runner.run(job, hprof, data, "parseidx", "histogram",
                                 "parse_trigger.csv",
                                 abort=self._store.abort_event(dump_id))
            if p and os.path.exists(p):
                os.remove(p)   # duplicates the data bundle's histogram.csv
            raws, _ = raws_zsts(dump_dir)
            if not raws:
                raise RuntimeError("MAT parse produced no index files")
            self._store.update_meta(dump_id, lambda m: m.update(indexes="local"))
            self._store.machine_transition(dump_id, "indexes", None,
                                           machine.Comp(machine.DONE))
            self.invalidate(dump_id)
        except core.Aborted:
            raise   # cancel/preempt already owns the state
        except Exception as e:   # noqa: BLE001 - the component's ERROR
            self._store.machine_transition(
                dump_id, "indexes", machine.IN_PROGRESS,
                machine.Comp(machine.ERROR, error=str(e)))
            raise
        finally:
            self._store.kick(dump_id)

    def submit_bootstrap(self, dump_id):
        """INDEX job: the local MAT bootstrap (histogram + dominators — the
        local-build stage of the machine's data component; the parse it
        triggers also produces the index set). Outcome is CAS-reported to
        the machine: success -> data DONE (+ indexes DONE when not already
        handled), failure -> data ERROR. Also called directly by CI
        (backend/ci.py) — the transitions are no-ops without a machine."""

        def fn(job):
            try:
                self._bootstrap(job, dump_id)
            except core.Aborted:
                raise
            except Exception as e:   # noqa: BLE001 - the component's ERROR
                self._store.machine_transition(
                    dump_id, "data", machine.IN_PROGRESS,
                    machine.Comp(machine.ERROR, error=str(e)))
                raise
            else:
                self._store.machine_transition(dump_id, "data", None,
                                               machine.Comp(machine.DONE))
                self._store.machine_transition(dump_id, "indexes", (machine.NEW,),
                                               machine.Comp(machine.DONE))
            finally:
                self._store.kick(dump_id)

        return self._jobs.submit(core.JobKind.INDEX, dump_id, "bootstrap", fn)

    def _bootstrap(self, job, dump_id):
        """Global extracts for one dump -> <dump>/data/analysis.db. Resumable
        at the marker level (an ingested extract is never re-run). Runs while
        the data component is still missing (dir_of hands out the dir in any
        state; the query-side gates live in _data_dir/_data_early)."""
        dump_dir = self._store.dir_of(dump_id)
        data = os.path.join(dump_dir, "data")
        os.makedirs(data, exist_ok=True)
        hprof = self._hprof(dump_id)
        if not hprof:
            raise RuntimeError("no .hprof in the dump dir")
        abort = self._store.abort_event(dump_id)
        log = lambda m: self._jobs.log(job, m)
        log(f"analyzing {hprof} -> {dump_dir} (mat-jobs={MAT_JOBS})")
        db = dbmod.open_db(data)
        try:
            # the histogram runs first and alone: when the MAT indexes are missing the
            # first query triggers the full hprof parse, and concurrent parsers would
            # clobber each other's index files
            if not dbmod.has_marker(db, "part:hist"):
                p = self._runner.run(job, hprof, data, "histogram", "histogram",
                                     "histogram.csv", abort=abort)
                if p:
                    dbmod.ingest_hist(db, p)
            log("  histogram done (parse complete)")

            # dominator groupings are independent — run in parallel
            tasks = []
            if not dbmod.has_marker(db, "part:dom"):
                tasks.append(lambda: self._runner.run(
                    job, hprof, data, "domclass", "dominator_tree -groupBy BY_CLASS",
                    "dominator_by_class.csv", abort=abort))
            if not os.path.exists(os.path.join(data, "dominator_by_package.csv")):
                tasks.append(lambda: self._runner.run(
                    job, hprof, data, "dompkg", "dominator_tree -groupBy BY_PACKAGE",
                    "dominator_by_package.csv", abort=abort))
            if tasks:
                _par(tasks)
            p = os.path.join(data, "dominator_by_class.csv")
            if os.path.exists(p):
                dbmod.ingest_dom(db, p)

            # module count = DefaultScriptHandler instances (fallback 3010)
            modules = 3010
            for name, cnt, _s, _r in dbmod.read_dom(db):
                if name.endswith("DefaultScriptHandler_Decorated"):
                    modules = cnt

            def record(m):
                m["modules"] = modules   # merge — don't clobber prior on-demand analyses
                m["dump"] = os.path.basename(hprof)

            self._store.update_meta(dump_id, record)
            self.invalidate(dump_id)
            log(f"data: {data}")
        finally:
            db.close()
