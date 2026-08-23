"""Store + engine tests over synthetic tmpdir dumps. No MAT, no network:
MatRunner is constructed but never invoked (queries read CSVs, not the hprof).

State is the machine's business now: fixtures plant ARTIFACTS (hprof, data
CSVs, .dl parts) and let the store's observation/inference derive states —
exactly like a restart would."""
import os
import tempfile
import time
import unittest

from backend import core, machine
from backend.jobs import InMemoryJobRegistry
from backend.localstore import (COMPACT_HOLD_MAX, MARKER, FsDumpStore,
                                compact_dir)
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

    def make_dump(self, dump_id="run-t", with_data=True, with_hprof=True):
        """A READY dump: hprof + data bundle. The machine infers DONE/DONE
        from the artifacts — no state is written by hand."""
        d = os.path.join(self.tmp.name, dump_id)
        os.makedirs(os.path.join(d, "data"), exist_ok=True)
        if with_data:
            with open(os.path.join(d, "data", "histogram.csv"), "w") as f:
                f.write(HIST)
            with open(os.path.join(d, "data", "dominator_by_class.csv"), "w") as f:
                f.write(DOM)
        if with_hprof:
            with open(os.path.join(d, "daemon.hprof"), "wb") as f:
                f.write(b"hprof-bytes")
        self.store.update_meta(dump_id, lambda m: m.update(
            dump="daemon.hprof", modules=7))
        return d

    def set_machine(self, dump_id, **comps):
        """Persist component states, e.g. set_machine(id, dump=Comp(ERROR))."""
        def mut(meta):
            m = machine.machine_from(meta.get("machine"))
            m.wanted = True
            for name, c in comps.items():
                setattr(m, name, c)
            meta["machine"] = machine.machine_to(m)
        self.store.update_meta(dump_id, mut)


class TestStore(Fixture):
    def test_unknown_dump_404(self):
        with self.assertRaises(core.ApiError) as ctx:
            self.store.get("nope")
        self.assertEqual(ctx.exception.status, 404)
        with self.assertRaises(core.ApiError):
            self.store.start_download("nope")   # no remote source has it
        self.assertFalse(os.path.exists(os.path.join(self.tmp.name, "nope")))

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

    def test_component_errors_project_to_failed(self):
        """FAILED is a projection: whichever component is in ERROR carries
        the message; an explicit retry (start_download) resets it."""
        self.make_dump(with_hprof=False)   # data only
        self.set_machine("run-t", dump=machine.Comp(machine.ERROR,
                                                    error="net down"))
        info = self.store.get("run-t")
        self.assertIs(info.state, core.DumpState.FAILED)
        self.assertEqual(info.error, "net down")
        self.set_machine("run-t", dump=machine.Comp(machine.CANCELLED))
        info = self.store.get("run-t")
        self.assertIs(info.state, core.DumpState.FAILED)
        self.assertIn("cancelled", info.error)

    def test_index_error_stays_ready(self):
        """The MAT indexes are lazy: their ERROR never drags a usable dump
        below READY."""
        self.make_dump()
        self.set_machine("run-t", dump=machine.Comp(machine.DONE),
                         data=machine.Comp(machine.DONE),
                         indexes=machine.Comp(machine.ERROR, error="bad tar"))
        self.assertIs(self.store.get("run-t").state, core.DumpState.READY)

    def test_delete_rules(self):
        """Delete works in any state: in-flight work is cancelled first
        (the abort machinery), then the dir goes away."""
        self.make_dump()
        self.set_machine("run-t", dump=machine.Comp(machine.DOWNLOADING))
        self.store.delete("run-t")   # no 409 anymore — cancel + delete
        self.assertFalse(os.path.exists(os.path.join(self.tmp.name, "run-t")))

    def test_cancel_marks_in_progress_components(self):
        self.make_dump()
        self.set_machine("run-t", dump=machine.Comp(machine.DONE),
                         data=machine.Comp(machine.DONE),
                         indexes=machine.Comp(machine.DOWNLOADING))
        self.store.cancel("run-t")
        m = machine.machine_from(self.store.read_meta("run-t")["machine"])
        self.assertEqual(m.indexes.s, machine.CANCELLED)
        self.assertEqual(m.dump.s, machine.DONE)   # done components untouched

    def test_tags(self):
        self.make_dump()
        tags = self.store.set_tags("run-t", ["base", " idea ", "base"])
        self.assertEqual(tags, ["base", "idea"])   # stripped + deduped
        self.assertEqual(self.store.user_tags(), {"run-t": ["base", "idea"]})
        # persisted in the root sidecar — a fresh store over the same root sees them
        other = FsDumpStore(self.tmp.name, self.jobs, [])
        self.assertEqual(other.user_tags(), {"run-t": ["base", "idea"]})
        # remote ids (no local dir) are taggable too
        self.store.set_tags("run-99-candidate", ["candidate"])
        self.assertEqual(self.store.user_tags()["run-99-candidate"], ["candidate"])
        with self.assertRaises(core.ApiError):
            self.store.set_tags("run-t", "base")        # not a list
        with self.assertRaises(core.ApiError):
            self.store.set_tags("run-t", ["bad!tag"])   # invalid chars
        with self.assertRaises(core.ApiError):
            self.store.set_tags("run-t", [f"t{i}" for i in range(25)])  # over MAX_TAGS
        self.store.set_tags("run-t", [])                # clears
        self.assertNotIn("run-t", self.store.user_tags())
        # deleting a dump drops its tags
        self.store.set_tags("run-t", ["x"])
        self.store.delete("run-t")
        self.assertNotIn("run-t", self.store.user_tags())

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


class TestCompactHold(unittest.TestCase):
    """The compact-hold API: an agent pins a dump's restored indexes
    against autocompact for a bounded time (a restore is minutes of zstd —
    a back-to-back query burst must not pay it per call)."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.jobs = FakeJobs()
        self.store = FsDumpStore(self.tmp.name, self.jobs, [])
        self.store.init()

    def make_compacted(self, dump_id="run-t"):
        """A READY dump with raws over a compacted set + the marker —
        autocompact's exact trigger (indexes infer DONE from artifacts)."""
        d = os.path.join(self.tmp.name, dump_id)
        os.makedirs(os.path.join(d, "data"), exist_ok=True)
        for rel in ("daemon.hprof", "a.index", "a.index.zst"):
            with open(os.path.join(d, rel), "wb") as f:
                f.write(b"x")
        with open(os.path.join(d, "data", "histogram.csv"), "w") as f:
            f.write(HIST)
        with open(os.path.join(d, "data", "dominator_by_class.csv"), "w") as f:
            f.write(DOM)
        with open(os.path.join(d, MARKER), "w") as f:
            f.write("x")
        return d

    def test_hold_blocks_autocompact(self):
        self.make_compacted()
        self.store.hold_compact("run-t", 600)
        self.store.reconcile("run-t")
        self.assertEqual(self.jobs.submitted, [])          # no COMPACT
        self.assertTrue(self.store.release_compact("run-t"))
        self.store.reconcile("run-t")
        self.assertEqual([j.kind for j in self.jobs.submitted],
                         [core.JobKind.COMPACT])

    def test_hold_expires(self):
        self.make_compacted()
        self.store.hold_compact("run-t", 0.01)
        time.sleep(0.05)
        self.store.reconcile("run-t")
        self.assertEqual([j.kind for j in self.jobs.submitted],
                         [core.JobKind.COMPACT])

    def test_validation_default_and_idempotent_release(self):
        self.make_compacted()
        for bad in (0, -5, COMPACT_HOLD_MAX + 1, "abc"):
            with self.assertRaises(core.ApiError) as ctx:
                self.store.hold_compact("run-t", bad)
            self.assertEqual(ctx.exception.status, 400)
        with self.assertRaises(core.ApiError) as ctx:
            self.store.hold_compact("nope", 60)
        self.assertEqual(ctx.exception.status, 404)
        with self.assertRaises(core.ApiError) as ctx:
            self.store.release_compact("nope")
        self.assertEqual(ctx.exception.status, 404)
        until = self.store.hold_compact("run-t")   # default = the 1h cap
        self.assertAlmostEqual(until, time.time() + COMPACT_HOLD_MAX, delta=5)
        self.assertTrue(self.store.release_compact("run-t"))
        self.assertFalse(self.store.release_compact("run-t"))   # idempotent


class TestPinHprof(Fixture):
    OLD = 1_700_000_000_000_000_000   # fixed past mtime, ns

    def _mk(self, d, name, mtime_ns=None):
        p = os.path.join(d, name)
        with open(p, "wb") as f:
            f.write(b"x")
        if mtime_ns is not None:
            os.utime(p, ns=(mtime_ns, mtime_ns))
        return p

    def test_newer_hprof_pinned_and_debris_removed(self):
        d = self.make_dump()
        hprof = self._mk(d, "daemon.hprof")                    # mtime = now
        idx = self._mk(d, "daemon.index", self.OLD)
        lock = self._mk(d, "daemon.lock.index")
        lock_zst = self._mk(d, "daemon.lock.index.zst")
        tmp = self._mk(d, "daemon.temp.idx.index")
        runner = self.engine._runner
        runner._pin_hprof(hprof, d, lambda m: None)
        self.assertEqual(os.stat(hprof).st_mtime_ns, self.OLD)
        self.assertFalse(os.path.exists(lock))
        self.assertFalse(os.path.exists(lock_zst))
        self.assertFalse(os.path.exists(tmp))
        self.assertTrue(os.path.exists(idx))

    def test_older_hprof_untouched(self):
        d = self.make_dump()
        hprof = self._mk(d, "daemon.hprof", self.OLD)
        self._mk(d, "daemon.index", self.OLD + 1000)
        self.engine._runner._pin_hprof(hprof, d, lambda m: None)
        self.assertEqual(os.stat(hprof).st_mtime_ns, self.OLD)


class TestEngine(Fixture):
    def test_queries_need_ready(self):
        self.make_dump(with_data=False, with_hprof=False)   # garbage dir
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
    """Captures submissions without running them — startup-reconcile tests
    only care what got resubmitted, not the stage itself."""
    def __init__(self):
        self.submitted = []

    def submit(self, kind, dump_id, detail, fn):
        job = core.Job(id=len(self.submitted) + 1, kind=kind,
                       dump_id=dump_id, detail=detail)
        self.submitted.append(job)
        return job

    def log(self, job, line):
        pass

    def list(self, limit=30):
        return []


class FakeSource:
    def __init__(self, plan=None, error=None):
        self.plan, self.error = plan, error

    def download_plan(self, dump_id):
        if self.error is not None:
            raise self.error
        return self.plan


class TestStartupReconcile(unittest.TestCase):
    """Process died with work in flight -> reconcile_all() re-enters the
    in-progress stages (their artifacts re-validate), idles when the remote
    can't help yet, and leaves ERROR/CANCELLED for the user."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.jobs = FakeJobs()

    def make_store(self, sources=()):
        store = FsDumpStore(self.tmp.name, self.jobs, list(sources))
        store.init()
        return store

    def make_dump(self, store, dump_id, **comps):
        d = os.path.join(self.tmp.name, dump_id)
        os.makedirs(os.path.join(d, "data"), exist_ok=True)
        def mut(meta):
            m = machine.Machine(wanted=True)
            for name, c in comps.items():
                setattr(m, name, c)
            meta["machine"] = machine.machine_to(m)
        store.update_meta(dump_id, mut)
        return d

    def plan(self, dump_id, data=False, indexes=False):
        part = core.Part(name="p", index=0, size=1, url="mem://p")
        return core.DownloadPlan(
            dump_id=dump_id,
            data_bundle=part if data else None,
            hprof_parts=(part,),
            index_parts=(part,) if indexes else (),
            manifest={})

    def test_reenters_interrupted_download(self):
        store = self.make_store(sources=[FakeSource(self.plan("run-d"))])
        self.make_dump(store, "run-d", dump=machine.Comp(machine.DOWNLOADING))
        store.reconcile_all()
        self.assertEqual([(j.kind, j.dump_id, j.detail) for j in self.jobs.submitted],
                         [(core.JobKind.DOWNLOAD, "run-d", "dump")])
        self.assertIs(store.get("run-d").state, core.DumpState.DOWNLOADING)

    def test_fill_from_late_published_release(self):
        """Interrupted with the hprof present but data missing: the late-
        published release fills it — no local build, no user round-trip."""
        store = self.make_store(sources=[FakeSource(self.plan("run-i", data=True,
                                                              indexes=True))])
        d = self.make_dump(store, "run-i", data=machine.Comp(machine.DOWNLOADING))
        with open(os.path.join(d, "daemon.hprof"), "wb") as f:
            f.write(b"x")
        store.reconcile_all()
        self.assertEqual({j.detail for j in self.jobs.submitted},
                         {"data", "indexes"})
        self.assertIs(store.get("run-i").state, core.DumpState.INDEXING)

    def test_nothing_published_idles_quietly(self):
        """Nothing local starts unprompted: no data bundle anywhere -> no
        jobs, the dump waits in INDEXING for the poll or an explicit retry."""
        store = self.make_store(sources=[FakeSource(self.plan("run-i"))])
        d = self.make_dump(store, "run-i")
        with open(os.path.join(d, "daemon.hprof"), "wb") as f:
            f.write(b"x")
        store.reconcile_all()
        self.assertEqual(self.jobs.submitted, [])
        self.assertIs(store.get("run-i").state, core.DumpState.INDEXING)

    def test_gone_from_source_is_an_error_not_a_zombie(self):
        """The run release itself is confirmed gone: the dump component is a
        real ERROR (user input), not an endless retry."""
        store = self.make_store(sources=[FakeSource(None)])
        self.make_dump(store, "run-g", dump=machine.Comp(machine.DOWNLOADING))
        store.reconcile_all()
        info = store.get("run-g")
        self.assertIs(info.state, core.DumpState.FAILED)
        self.assertEqual(self.jobs.submitted, [])

    def test_upstream_error_is_transient_not_fatal(self):
        """A network hiccup during startup reconcile must NOT fail the dump:
        the machine idles and a later tick re-queries."""
        err = core.ApiError("upstream", "github down", 502)
        store = self.make_store(sources=[FakeSource(error=err)])
        self.make_dump(store, "run-u", dump=machine.Comp(machine.DOWNLOADING))
        store.reconcile_all()
        self.assertIs(store.get("run-u").state, core.DumpState.DOWNLOADING)
        self.assertEqual(self.jobs.submitted, [])

    def test_terminal_states_stay_put(self):
        store = self.make_store()
        d = self.make_dump(store, "run-r")
        for name, content in (("daemon.hprof", b"h"),
                              ("data/histogram.csv", HIST.encode()),
                              ("data/dominator_by_class.csv", DOM.encode())):
            with open(os.path.join(d, name), "wb") as f:
                f.write(content)
        self.make_dump(store, "run-f",
                       dump=machine.Comp(machine.ERROR, error="boom"))
        store.reconcile_all()
        self.assertEqual(self.jobs.submitted, [])
        self.assertIs(store.get("run-r").state, core.DumpState.READY)
        self.assertIs(store.get("run-f").state, core.DumpState.FAILED)


if __name__ == "__main__":
    unittest.main()
