"""Store + engine tests over synthetic tmpdir dumps. No MAT, no network:
MatRunner is constructed but never invoked (queries read CSVs, not the hprof)."""
import json
import os
import tempfile
import unittest

from backend import core
from backend.jobs import InMemoryJobRegistry
from backend.localstore import FsDumpStore, compact_dir, MARKER
from backend.mat import MatQueryEngine

HIST = ("Class Name,Objects,Shallow Heap\n"
        "org.gradle.Big,5,200000\n"
        "com.android.App,3,60000\n")
DOM = ("Class Name,Objects,Shallow Heap,Retained Heap,x\n"
       "org.gradle.Big,5,200000,900000,z\n"
       "com.android.App,3,60000,70000,z\n")


class Fixture(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.jobs = InMemoryJobRegistry()
        self.store = FsDumpStore(self.tmp.name, self.jobs, [])
        self.store.init()
        self.engine = MatQueryEngine(self.store, self.jobs)

    def make_dump(self, dump_id="run-t", state="ready", with_data=True):
        d = os.path.join(self.tmp.name, dump_id)
        os.makedirs(os.path.join(d, "data"), exist_ok=True)
        if with_data:
            with open(os.path.join(d, "data", "histogram.csv"), "w") as f:
                f.write(HIST)
            with open(os.path.join(d, "data", "dominator_by_class.csv"), "w") as f:
                f.write(DOM)
        self.store.update_meta(dump_id, lambda m: m.update(
            state=state, dump="daemon.hprof", modules=7))
        return d


class TestStore(Fixture):
    def test_unknown_dump_404(self):
        with self.assertRaises(core.ApiError) as ctx:
            self.store.get("nope")
        self.assertEqual(ctx.exception.status, 404)
        with self.assertRaises(core.ApiError):
            self.store.start_download("nope")   # no remote source has it

    def test_state_roundtrip(self):
        self.make_dump()
        info = self.store.get("run-t")
        self.assertIs(info.state, core.DumpState.READY)
        self.assertEqual(self.store.read_meta("run-t")["modules"], 7)

    def test_dir_without_state_is_failed(self):
        os.makedirs(os.path.join(self.tmp.name, "run-x"))
        info = self.store.get("run-x")
        self.assertIs(info.state, core.DumpState.FAILED)
        self.assertIn("no recorded state", info.error)

    def test_dl_dir_without_state_is_downloading(self):
        d = os.path.join(self.tmp.name, "run-d")
        os.makedirs(os.path.join(d, ".dl"), exist_ok=True)
        with open(os.path.join(d, ".dl", "part-0"), "wb") as f:
            f.write(b"x" * 100)
        info = self.store.get("run-d")
        self.assertIs(info.state, core.DumpState.DOWNLOADING)
        self.assertEqual(info.progress, (100, 0))

    def test_delete_rules(self):
        self.make_dump()
        self.store.update_meta("run-t", lambda m: m.update(state="downloading"))
        with self.assertRaises(core.ApiError) as ctx:
            self.store.delete("run-t")
        self.assertEqual(ctx.exception.status, 409)
        self.store.update_meta("run-t", lambda m: m.update(state="ready"))
        self.store.delete("run-t")
        self.assertFalse(os.path.exists(os.path.join(self.tmp.name, "run-t")))

    def test_compact_drops_untouched_raw(self):
        """mtime convention: raw == zst mtime -> raw dropped, no recompress."""
        d = self.make_dump()
        raw = os.path.join(d, "a.index")
        zst = raw + ".zst"
        for p in (raw, zst):
            with open(p, "wb") as f:
                f.write(b"payload")
        ns = os.stat(zst).st_mtime_ns
        os.utime(raw, ns=(ns, ns))
        compact_dir(d)
        self.assertFalse(os.path.exists(raw))
        self.assertTrue(os.path.exists(zst))
        self.assertTrue(os.path.exists(os.path.join(d, MARKER)))


class TestPinHprof(Fixture):
    OLD = 1_700_000_000_000_000_000   # fixed past mtime, ns

    def _mk(self, name, mtime_ns=None):
        p = os.path.join(self.make_dump(), name)
        with open(p, "wb") as f:
            f.write(b"x")
        if mtime_ns is not None:
            os.utime(p, ns=(mtime_ns, mtime_ns))
        return p

    def test_newer_hprof_pinned_and_debris_removed(self):
        hprof = self._mk("daemon.hprof")                       # mtime = now
        idx = self._mk("daemon.index", self.OLD)
        lock = self._mk("daemon.lock.index")
        lock_zst = self._mk("daemon.lock.index.zst")
        tmp = self._mk("daemon.temp.idx.index")
        runner = self.engine._runner
        d = os.path.dirname(hprof)
        runner._pin_hprof(hprof, d, lambda m: None)
        self.assertEqual(os.stat(hprof).st_mtime_ns, self.OLD)
        self.assertFalse(os.path.exists(lock))
        self.assertFalse(os.path.exists(lock_zst))
        self.assertFalse(os.path.exists(tmp))
        self.assertTrue(os.path.exists(idx))

    def test_older_hprof_untouched(self):
        hprof = self._mk("daemon.hprof", self.OLD)
        self._mk("daemon.index", self.OLD + 1000)
        self.engine._runner._pin_hprof(hprof, os.path.dirname(hprof), lambda m: None)
        self.assertEqual(os.stat(hprof).st_mtime_ns, self.OLD)


class TestEngine(Fixture):
    def test_queries_need_ready(self):
        self.make_dump(state="failed")
        with self.assertRaises(core.ApiError) as ctx:
            self.engine.trees("run-t")
        self.assertEqual(ctx.exception.status, 409)

    def test_trees_and_classes(self):
        self.make_dump()
        t = self.engine.trees("run-t")
        self.assertEqual(t["stats"]["totalObjects"], 8)
        self.assertEqual(t["stats"]["totalRetained"], 970000)
        self.assertEqual(t["stats"]["modules"], 7)
        self.assertIn("dom", t["trees"])

        c = self.engine.classes("run-t")
        self.assertEqual(c["total"], 2)
        self.assertEqual(c["pages"], 1)
        self.assertEqual(c["rows"][0]["disp"], "org.gradle.Big")   # -s default

        f = self.engine.classes("run-t", filter="android")
        self.assertEqual([r["disp"] for r in f["rows"]], ["com.android.App"])

        s = self.engine.classes("run-t", sort="name")
        self.assertEqual([r["disp"] for r in s["rows"]],
                         ["com.android.App", "org.gradle.Big"])

    def test_compare(self):
        self.make_dump("run-a")
        self.make_dump("run-b")
        out = self.engine.compare("run-a", "run-b")
        self.assertEqual(out["old"]["totalObjects"], 8)
        self.assertEqual(out["new"]["totalObjects"], 8)
        self.assertTrue(all(r[6] == 0 for r in out["rows"]))   # identical dumps

    def test_composition_not_analyzed(self):
        self.make_dump()
        self.assertIsNone(self.engine.composition("run-t", "org.gradle.Big"))
        self.assertIsNone(self.engine.anatomy("run-t", "org.gradle.Big"))


class FakeJobs:
    """Captures submissions without running them — recovery tests only care
    what got resubmitted, not the download itself."""
    def __init__(self):
        self.submitted = []

    def submit(self, kind, dump_id, detail, fn):
        job = core.Job(id=len(self.submitted) + 1, kind=kind,
                       dump_id=dump_id, detail=detail)
        self.submitted.append(job)
        return job

    def log(self, job, line):
        pass


class FakeSource:
    def __init__(self, plan=None, error=None):
        self.plan, self.error = plan, error

    def download_plan(self, dump_id):
        if self.error is not None:
            raise self.error
        return self.plan


class TestRecovery(unittest.TestCase):
    """Process died with a busy state persisted -> recover_interrupted()
    resubmits the work, or fails the dump when resubmission is impossible."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.jobs = FakeJobs()

    def make_store(self, sources=(), indexer=None):
        store = FsDumpStore(self.tmp.name, self.jobs, list(sources))
        store.indexer = indexer
        store.init()
        return store

    def make_dump(self, store, dump_id, state):
        d = os.path.join(self.tmp.name, dump_id)
        os.makedirs(os.path.join(d, "data"), exist_ok=True)
        store.update_meta(dump_id, lambda m: m.update(state=state))
        return d

    def plan(self, dump_id):
        return core.DownloadPlan(dump_id=dump_id, data_bundle=None,
                                 hprof_parts=(), index_parts=(), manifest={})

    def test_resubmits_downloading(self):
        store = self.make_store(sources=[FakeSource(self.plan("run-d"))])
        self.make_dump(store, "run-d", "downloading")
        store.recover_interrupted()
        self.assertEqual([(j.kind, j.dump_id) for j in self.jobs.submitted],
                         [(core.JobKind.DOWNLOAD, "run-d")])
        self.assertIs(store.get("run-d").state, core.DumpState.DOWNLOADING)

    def test_resubmits_assembling(self):
        store = self.make_store(sources=[FakeSource(self.plan("run-a"))])
        self.make_dump(store, "run-a", "assembling")
        store.recover_interrupted()
        self.assertEqual(len(self.jobs.submitted), 1)

    def test_resubmits_indexing_via_indexer(self):
        calls = []
        store = self.make_store(indexer=lambda dump_id: calls.append(dump_id)
                                or core.Job(id=1, kind=core.JobKind.INDEX,
                                            dump_id=dump_id))
        self.make_dump(store, "run-i", "indexing")
        store.recover_interrupted()
        self.assertEqual(calls, ["run-i"])
        self.assertEqual(self.jobs.submitted, [])   # no remote needed

    def test_fails_when_no_source_has_it(self):
        store = self.make_store()
        self.make_dump(store, "run-g", "downloading")
        store.recover_interrupted()
        info = store.get("run-g")
        self.assertIs(info.state, core.DumpState.FAILED)
        self.assertIn("interrupted downloading", info.error)
        self.assertEqual(self.jobs.submitted, [])

    def test_fails_on_upstream_error(self):
        err = core.ApiError("upstream", "github down", 502)
        store = self.make_store(sources=[FakeSource(error=err)])
        self.make_dump(store, "run-u", "downloading")
        store.recover_interrupted()
        self.assertIs(store.get("run-u").state, core.DumpState.FAILED)

    def test_leaves_terminal_states_alone(self):
        store = self.make_store()
        self.make_dump(store, "run-r", "ready")
        self.make_dump(store, "run-f", "failed")
        store.recover_interrupted()
        self.assertEqual(self.jobs.submitted, [])
        self.assertIs(store.get("run-r").state, core.DumpState.READY)
        self.assertIs(store.get("run-f").state, core.DumpState.FAILED)


if __name__ == "__main__":
    unittest.main()
