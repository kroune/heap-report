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


if __name__ == "__main__":
    unittest.main()
