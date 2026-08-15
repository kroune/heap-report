"""backend/mat/engine — MatQueryEngine: the MAT-backed core.QueryEngine +
local bootstrap (LocalIndexer role).

  - Payload caches live here and only here, keyed on the source files' mtimes AND
    meta's state; invalidate(dump_id) drops everything cached for a dump. The
    store/kernel calls invalidate() when a dump's data changes (download,
    bootstrap, compact, finished analysis).
  - No on-disk payload caches: only the store writes a dump dir. The in-memory
    payload cache + the separately cached CSV parse cover the re-parse cost.
  - A failed extraction raises -> the ANALYZE job is FAILED with the real error
    and meta.json is never updated for data that does not exist.

All dump-dir paths come from store.dir_of (READY-only); all meta.json writes go
through store.update_meta (single writer). Every MAT subprocess goes through
extract.MatRunner.
"""
from __future__ import annotations

import glob
import json
import os
import re
import threading

from .. import core
from .parsing import (_analysis_index_build, _anat_src_load, _anat_srcs,
                      _merge_fams, _parse_dom, _parse_hist, _read_csv,
                      _rs_totals_build)
from .payloads import (PROXIES, _anat2_build, _anatomy_build, _anatomy_diff,
                       _class_table_build, _composition_build, _stats_build,
                       _trees_build, _waterfall)
from .extract import (EDGE_FULL_CAP, IDS_LIMIT, MAT_JOBS, MAX_EDGE,
                      MAX_STRINGS, SAMPLES, MatRunner, _par, sample_even,
                      subselect, suffix)

PAGE = 200                    # classes() page size


class MatQueryEngine:
    """core.QueryEngine over the local dump store, plus the local MAT bootstrap
    (LocalIndexer role). All dump-dir paths come from store.dir_of (READY-only);
    all meta.json writes go through store.update_meta (single writer)."""

    def __init__(self, store: core.LocalDumpStore, jobs: core.JobRegistry):
        self._store = store
        self._jobs = jobs
        self._runner = MatRunner(jobs)
        self._lock = threading.RLock()
        self._cache = {}            # key tuple -> (fingerprint, payload)

    # ---------------------------------------------------------- plumbing

    def _data_dir(self, dump_id):
        """Query-side entry: READY only (dir_of also hands out INDEXING dirs to
        the store-sanctioned bootstrap job — that path never goes through here)."""
        info = self._store.get(dump_id)
        if info.state is not core.DumpState.READY:
            raise core.ApiError("bad_state",
                                f"dump {dump_id} is {info.state.value}, queries need ready", 409)
        return os.path.join(self._store.dir_of(dump_id), "data")

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
        with self._lock:
            ent = self._cache.get(key)
            if ent is not None and ent[0] == fp:
                return ent[1]
        val = loader()
        with self._lock:
            self._cache[key] = (fp, val)
        return val

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

    def _load_hist(self, dump_id):
        p = os.path.join(self._data_dir(dump_id), "histogram.csv")
        return self._cached((dump_id, "hist"), self._fp(dump_id, [p]),
                            lambda: _parse_hist(p))

    def _load_dom(self, dump_id):
        p = os.path.join(self._data_dir(dump_id), "dominator_by_class.csv")
        return self._cached((dump_id, "dom"), self._fp(dump_id, [p]),
                            lambda: _parse_dom(p))

    def _analysis_index(self, dump_id):
        data = self._data_dir(dump_id)
        mp = os.path.join(data, "meta.json")
        return self._cached((dump_id, "anaidx"), self._fp(dump_id, [mp]),
                            lambda: _analysis_index_build(data, self._meta(dump_id)))

    def _rs_totals(self, dump_id):
        data = self._data_dir(dump_id)
        mp = os.path.join(data, "meta.json")
        return self._cached((dump_id, "rstot"), self._fp(dump_id, [mp]),
                            lambda: _rs_totals_build(data, self._meta(dump_id),
                                                     self._analysis_index(dump_id)))

    # ---------------------------------------------------------- queries

    def trees(self, dump_id):
        data = self._data_dir(dump_id)
        paths = [os.path.join(data, n) for n in
                 ("histogram.csv", "dominator_by_class.csv", "meta.json")]
        return self._cached(
            (dump_id, "trees"), self._fp(dump_id, paths),
            lambda: {"stats": _stats_build(self._load_hist(dump_id), self._load_dom(dump_id),
                                           self._meta(dump_id), self._analysis_index(dump_id)),
                     "trees": _trees_build(self._load_hist(dump_id), self._load_dom(dump_id))})

    def _class_table(self, dump_id):
        data = self._data_dir(dump_id)
        paths = [os.path.join(data, n) for n in ("histogram.csv", "meta.json")]
        return self._cached((dump_id, "classtable"), self._fp(dump_id, paths),
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
            rows.sort(key=lambda r: (r["r"] is None, -(r["r"] or 0)))
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
        meta = self._meta(dump_id)
        p = os.path.join(data, meta.get("rs", {}).get(st["key"], f"rs_{st['key']}.csv"))
        if not os.path.exists(p):
            return None
        return self._cached((dump_id, "comp", st["key"]),
                            self._fp(dump_id, [p, os.path.join(data, "meta.json")]),
                            lambda: _composition_build(p))

    def _anat_src(self, dump_id, key, K):
        data = self._data_dir(dump_id)
        srcs = _anat_srcs(data, key, K)
        if not srcs:
            return None
        return self._cached((dump_id, "anatsrc", key, K), self._fp(dump_id, srcs),
                            lambda: _anat_src_load(data, key, srcs, self._meta(dump_id)))

    def anatomy(self, dump_id, cls, version=1, samples=None):
        """version 1 = aggregated named-reference tree, 2 = full-graph
        reference tree. None = not analyzed."""
        data = self._data_dir(dump_id)
        st = self._analysis_index(dump_id).get(cls)
        if not st or not st["anat"]:
            return None
        key = st["key"]
        avail = st["anat"]
        K = samples if samples in avail else avail[-1]
        if not _anat_srcs(data, key, K):
            return None
        build, max_depth, max_kids = (_anat2_build, 32, 40) if version == 2 \
            else (_anatomy_build, 14, 40)

        def load():
            src = self._anat_src(dump_id, key, K)
            return None if src is None else build(src, cls, K, avail, max_depth, max_kids)

        return self._cached((dump_id, "anat", version, key, K),
                            self._fp(dump_id, _anat_srcs(data, key, K)), load)

    def compare(self, a, b):
        """Not payload-cached at this level: it assembles cheap merges over the
        per-dump caches, which carry freshness."""
        da, db = self._data_dir(a), self._data_dir(b)
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
        anats = {}
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
        """Stable short key for a class, collision-free against meta."""
        classes = self._meta(dump_id).get("classes", {})
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
        FAILED job with the real error and is never recorded in meta.json."""
        try:
            samples = max(1, min(1024, int(samples)))
        except (TypeError, ValueError):
            samples = SAMPLES
        known = {name for name, _, _ in self._load_hist(dump_id)}
        if cls not in known:
            raise core.ApiError("not_found", f"class not in histogram: {cls}", 404)
        hprof = self._hprof(dump_id)
        if not hprof:
            raise core.ApiError("bad_state", "hprof not found in the dump dir", 400)

        def fn(job):
            self._analyze_class(job, dump_id, hprof, cls, samples, with_anatomy)

        return self._jobs.submit(core.JobKind.ANALYZE, dump_id, cls, fn)

    def _analyze_class(self, job, dump_id, hprof, cls, samples, with_anatomy):
        """Retained-set composition + (optionally) reference-tree anatomy for one
        class. Resumable: existing CSVs are reused, so escalating to more samples
        only runs the missing extractions."""
        data = self._data_dir(dump_id)
        anat = os.path.join(data, "anat")
        os.makedirs(anat, exist_ok=True)
        key = self._alloc_key(dump_id, cls)
        log = lambda m: self._jobs.log(job, m)
        log(f"analyzing {cls} (key={key}, samples={samples}, anatomy={with_anatomy})")
        # retained-set composition and the full id list are independent — overlap them
        tasks = [lambda: self._runner.run(job, hprof, data, suffix("rs", key),
                                        f"show_retained_set {cls}", f"rs_{key}.csv")]
        if with_anatomy:
            tasks.append(lambda: self._runner.run(job, hprof, data, suffix("idsall", key),
                                                  f'oql "SELECT s.@objectId FROM INSTANCEOF {cls} s"',
                                                  f"idsall_{key}.csv", limit=IDS_LIMIT))
        res = _par(tasks)
        if not res[0] or not os.path.exists(res[0]):
            raise RuntimeError("retained-set query failed")

        def record_rs(m):
            m.setdefault("classes", {})[key] = cls
            m.setdefault("rs", {})[key] = f"rs_{key}.csv"

        self._store.update_meta(dump_id, record_rs)
        if not with_anatomy:
            self.invalidate(dump_id)
            return
        # full id list -> evenly-spaced sample of K instances
        ids = []
        p = res[1]
        if p and os.path.exists(p):
            ids = [r[0] for r in _read_csv(p)[1:] if r and r[0].isdigit()]
        picked = sample_even(ids, samples)
        if not picked:
            raise RuntimeError("no instance ids")
        K = len(picked)
        # per-extraction sidecar so the anatomy can be rebuilt with the same ids
        with open(os.path.join(anat, f"{key}_s{K}.json"), "w") as f:
            json.dump({"key": key, "cls": cls, "samples": K, "ids": picked}, f)
        idx = ", ".join(f"outbounds(o)[{i}]" for i in range(MAX_EDGE))
        idx_full = ", ".join(f"outbounds(o)[{i}]" for i in range(EDGE_FULL_CAP))
        sub = subselect(cls, picked)
        # nodes / edges / fields scan the same retained set independently — run
        # concurrently. edgesfull captures complete outbounds for objects with
        # >MAX_EDGE refs (the plain edges query truncates at MAX_EDGE, which
        # silently orphans big-array children).
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
            if addrs:
                self._runner.run(job, hprof, anat, suffix("s2", f"{key}_s{K}"),
                        f'oql "SELECT toHex(o.@objectAddress), toString(o) FROM OBJECTS {",".join(addrs)} o"',
                        f"{key}_s{K}_strings.csv")

        def record_anat(m):
            ks = set(m.get("anatSamples", {}).get(key, []))
            ks.add(K)
            m.setdefault("anatSamples", {})[key] = sorted(ks)
            m.setdefault("ids", {})[key] = picked

        self._store.update_meta(dump_id, record_anat)
        self.invalidate(dump_id)
        log(f"done: {cls} ({K} samples)")

    # ---------------------------------------------------------- bootstrap (LocalIndexer)

    def submit_bootstrap(self, dump_id):
        """Submit an INDEX job running the local MAT bootstrap (histogram +
        dominators). On success the dump flips to "ready", on failure to "failed"
        with the error — both via store.update_meta. The kernel wires this to the
        store's INDEXING transition."""

        def fn(job):
            try:
                self._bootstrap(job, dump_id)
            except Exception as e:   # noqa: BLE001 - record, then let the job FAIL
                def fail(m, err=str(e)):
                    m["state"] = core.DumpState.FAILED.value
                    m["error"] = err

                try:
                    self._store.update_meta(dump_id, fail)
                except Exception as me:   # noqa: BLE001 - don't mask the real error
                    self._jobs.log(job, f"WARNING: could not record failure in meta: {me}")
                raise

            def ok(m):
                m["state"] = core.DumpState.READY.value
                m["error"] = None

            self._store.update_meta(dump_id, ok)

        return self._jobs.submit(core.JobKind.INDEX, dump_id, "bootstrap", fn)

    def _bootstrap(self, job, dump_id):
        """Global extracts for one dump -> <dump>/data/. Resumable.

        NOTE (integration): the dump is INDEXING, not READY, while this runs, so
        store.dir_of must hand out the dir to the INDEX job here — READY-only
        enforcement applies to the query methods above."""
        dump_dir = self._store.dir_of(dump_id)
        data = os.path.join(dump_dir, "data")
        os.makedirs(data, exist_ok=True)
        hprof = self._hprof(dump_id)
        if not hprof:
            raise RuntimeError("no .hprof in the dump dir")
        log = lambda m: self._jobs.log(job, m)
        log(f"analyzing {hprof} -> {dump_dir} (mat-jobs={MAT_JOBS})")

        # the histogram runs first and alone: when the MAT indexes are missing the
        # first query triggers the full hprof parse, and concurrent parsers would
        # clobber each other's index files
        self._runner.run(job, hprof, data, "histogram", "histogram", "histogram.csv")
        log("  histogram done (parse complete)")

        # dominator groupings are independent — run in parallel
        _par([
            lambda: self._runner.run(job, hprof, data, "domclass", "dominator_tree -groupBy BY_CLASS",
                                     "dominator_by_class.csv"),
            lambda: self._runner.run(job, hprof, data, "dompkg", "dominator_tree -groupBy BY_PACKAGE",
                                     "dominator_by_package.csv"),
        ])

        # module count = DefaultScriptHandler instances (fallback 3010)
        modules = 3010
        domp = os.path.join(data, "dominator_by_class.csv")
        if os.path.exists(domp):
            for r in _read_csv(domp)[1:]:
                if len(r) >= 4 and r[0].endswith("DefaultScriptHandler_Decorated") and r[1].isdigit():
                    modules = int(r[1])

        def record(m):
            m["modules"] = modules   # merge — don't clobber prior on-demand analyses
            m["dump"] = os.path.basename(hprof)

        self._store.update_meta(dump_id, record)
        self.invalidate(dump_id)
        log(f"data: {data}")
