"""Download-pipeline tests over the state-machine architecture: streaming
assembly overlap, stage-level retries (a transient network failure heals
inside the stage; an exhausted one is a component ERROR), resume from kept
.dl parts, lazy index acquisition (request_indexes / analyze), INDEXING fill
from a late-published release, and corruption healing (staged untar, manifest
validation, AssemblyError). No network (in-memory fake source, real gzip/tar
subprocesses) and no MAT (MatRunner is patched where a local parse runs)."""
import dataclasses
import gzip
import hashlib
import io
import json
import os
import tarfile
import tempfile
import threading
import time
import unittest

from backend import core, kernel, machine
from backend.jobs import InMemoryJobRegistry
from backend.localstore import (FsDumpStore, drop_untrusted_raws, parse_debris,
                                raws_zsts)
from backend.localstore import stages as stages_mod
from backend.localstore import transfer as transfer_mod
from backend.mat import MatQueryEngine
from backend.mat.extract import CorruptIndexError

HIST = ("Class Name,Objects,Shallow Heap\n"
        "org.gradle.Big,5,200000\n"
        "com.android.App,3,60000\n")
DOM = ("Class Name,Objects,Shallow Heap,Retained Heap,x\n"
       "org.gradle.Big,5,200000,900000,z\n"
       "com.android.App,3,60000,70000,z\n")
HPROF = b"".join(hashlib.sha256(i.to_bytes(4, "big")).digest()
                 for i in range(4000))   # 128 KB of incompressible bytes —
                                         # gz parts span multiple fetch chunks
IDX = {"daemon.index.zst": b"zst-one", "daemon.threads": b"threads"}


def _tar(names, mode="w"):
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode=mode) as tf:
        for name, content in names.items():
            ti = tarfile.TarInfo(name)
            ti.size = len(content)
            tf.addfile(ti, io.BytesIO(content))
    return buf.getvalue()


class FakeSource:
    """In-memory RemoteDumpSource: fetch honors Range offsets, optional per-part
    gates (block until an event), one-shot or persistent mid-stream failures.
    Records every call. `plan` may be a {dump_id: plan} dict."""

    def __init__(self, plan, payloads, gates=None, fail_once=(), fail_always=()):
        self.plan = plan
        self.payloads = payloads            # part name -> bytes
        self.gates = gates or {}            # part name -> threading.Event
        self.fail_once = set(fail_once)     # first fetch dies mid-stream, then heals
        self.fail_always = set(fail_always)  # every fetch dies mid-stream
        self.calls = []                     # (name, offset)
        self._failed = set()

    def download_plan(self, dump_id):
        if isinstance(self.plan, dict):
            return self.plan.get(dump_id)
        return self.plan

    def fetch(self, part, offset=0):
        self.calls.append((part.name, offset))
        gate = self.gates.get(part.name)
        if gate is not None:
            if not gate.wait(timeout=15):
                raise RuntimeError(f"test gate for {part.name} never opened")
        data = self.payloads[part.name]
        for pos in range(offset, len(data), 4096):
            yield data[pos:pos + 4096]
            if part.name in self.fail_always:
                raise ConnectionError("boom")
            if part.name in self.fail_once and part.name not in self._failed:
                self._failed.add(part.name)
                raise ConnectionError("boom")   # dies mid-stream, partial kept


def wait_job(jobs, job, timeout=20):
    deadline = time.time() + timeout
    while time.time() < deadline:
        j = jobs.get(job.id)
        if j.state in (core.JobState.DONE, core.JobState.FAILED):
            return j
        time.sleep(0.05)
    raise AssertionError(f"job #{job.id} did not finish in {timeout}s")


def wait_for(pred, timeout=15, what="condition"):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if pred():
            return
        time.sleep(0.05)
    raise AssertionError(f"timed out waiting for {what}")


def wait_all_idle(jobs, timeout=20):
    wait_for(lambda: all(j.state not in (core.JobState.QUEUED, core.JobState.RUNNING)
                         for j in jobs.list(limit=100)),
             timeout, "all jobs to finish")


def wait_ready(store, dump_id, timeout=20):
    wait_for(lambda: store.get(dump_id).state is core.DumpState.READY,
             timeout, f"{dump_id} to become READY")


def job_by_detail(jobs, dump_id, detail, timeout=10):
    deadline = time.time() + timeout
    while time.time() < deadline:
        for j in jobs.list(limit=100):
            if j.dump_id == dump_id and j.detail == detail:
                return j
        time.sleep(0.05)
    raise AssertionError(f"no ({dump_id}, {detail}) job submitted in {timeout}s")


def machine_of(store, dump_id):
    return machine.machine_from(store.read_meta(dump_id).get("machine"))


class Fixture(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.jobs = InMemoryJobRegistry()
        self._saved = (transfer_mod.DL_RETRIES, stages_mod.STAGE_ATTEMPTS,
                       stages_mod.STAGE_BACKOFF)
        stages_mod.STAGE_BACKOFF = 0   # stage retries are instant in tests

    def tearDown(self):
        (transfer_mod.DL_RETRIES, stages_mod.STAGE_ATTEMPTS,
         stages_mod.STAGE_BACKOFF) = self._saved

    def make_store(self, source):
        store = FsDumpStore(self.tmp.name, self.jobs, [source])
        store.init()
        return store

    def make_engine(self, store):
        """The kernel wiring: the store drives local MAT work through the
        engine (bootstrap as an INDEX job, the index parse INLINE)."""
        engine = MatQueryEngine(store, self.jobs)
        store.parser_inline = engine.parse_inline
        store.indexer = engine.submit_bootstrap
        return engine

    @staticmethod
    def make_plan(dump_id="run-t", with_indexes=True):
        """Payloads: data.tar.gz bundle, hprof.gz in 2 parts, index tar in 2."""
        def cut(data, prefix):
            step = (len(data) + 1) // 2
            parts, payloads = [], {}
            for i in range(2):
                chunk = data[i * step:(i + 1) * step]
                parts.append(core.Part(name=f"{prefix}{i}", index=i,
                                       size=len(chunk), url=f"mem://{prefix}{i}"))
                payloads[parts[-1].name] = chunk
            return parts, payloads

        data_tgz = _tar({"data/histogram.csv": HIST.encode(),
                         "data/dominator_by_class.csv": DOM.encode()}, mode="w:gz")
        hparts, payloads = cut(gzip.compress(HPROF), "daemon.hprof.gz.part-")
        tparts, tpayloads = cut(_tar(dict(IDX)), "indexes.tar.part-")
        payloads.update(tpayloads)
        payloads["data.tar.gz"] = data_tgz
        plan = core.DownloadPlan(
            dump_id=dump_id,
            data_bundle=core.Part(name="data.tar.gz", index=0,
                                  size=len(data_tgz), url="mem://data.tar.gz"),
            hprof_parts=tuple(hparts),
            index_parts=tuple(tparts) if with_indexes else (),
            manifest={"files": {n: len(c) for n, c in IDX.items()}}
            if with_indexes else {})
        return plan, payloads

    def make_ready_noindex(self, store, dump_id="run-t", idx_manifest=None):
        """READY dump (hprof + data bundle) without MAT indexes. States are
        inferred from the artifacts — no machine is written by hand."""
        d = os.path.join(self.tmp.name, dump_id)
        os.makedirs(os.path.join(d, "data"), exist_ok=True)
        with open(os.path.join(d, "data", "histogram.csv"), "w") as f:
            f.write(HIST)
        with open(os.path.join(d, "data", "dominator_by_class.csv"), "w") as f:
            f.write(DOM)
        with open(os.path.join(d, "daemon.hprof"), "wb") as f:
            f.write(HPROF)
        fields = {"dump": "daemon.hprof"}
        if idx_manifest is not None:
            fields["idx_manifest"] = idx_manifest
        store.update_meta(dump_id, lambda m: m.update(fields))
        return d

    def make_indexing(self, store, dump_id="run-t"):
        """An interrupted dump: hprof present, data bundle missing (the dir
        a crash leaves behind; wanted is inferred from the hprof)."""
        d = os.path.join(self.tmp.name, dump_id)
        os.makedirs(os.path.join(d, "data"), exist_ok=True)
        with open(os.path.join(d, "daemon.hprof"), "wb") as f:
            f.write(HPROF)
        store.update_meta(dump_id, lambda m: m.update(dump="daemon.hprof"))
        return d


class TestStreamingDownload(Fixture):
    def test_full_download_streams_and_ready(self):
        plan, payloads = self.make_plan()
        store = self.make_store(FakeSource(plan, payloads))
        job = store.start_download("run-t")
        self.assertEqual((job.kind, job.detail), (core.JobKind.DOWNLOAD, "dump"))
        wait_ready(store, "run-t")
        wait_all_idle(self.jobs)
        d = os.path.join(self.tmp.name, "run-t")
        with open(os.path.join(d, "daemon.hprof"), "rb") as f:
            self.assertEqual(f.read(), HPROF)   # parts concatenated in order
        for name, content in IDX.items():
            with open(os.path.join(d, name), "rb") as f:
                self.assertEqual(f.read(), content)
        self.assertEqual(store.read_meta("run-t")["indexes"], "remote")
        m = machine_of(store, "run-t")
        self.assertEqual(m.indexes.s, machine.DONE)
        self.assertTrue(m.indexes.compacted)
        store.reconcile("run-t")   # the sweeper pass drops leftover .dl parts
        self.assertFalse(os.path.exists(os.path.join(d, ".dl")))

    def test_assembly_overlaps_download(self):
        """The gunzip pipe must start consuming part-0 while part-1 is still
        blocked in the network — the whole point of streaming assembly."""
        plan, payloads = self.make_plan()
        gate = threading.Event()
        src = FakeSource(plan, payloads, gates={"daemon.hprof.gz.part-1": gate})
        store = self.make_store(src)
        job = store.start_download("run-t")
        try:
            wait_for(lambda: any("assemble: daemon.hprof.gz.part-0" in line
                                 for line in self.jobs.get(job.id).log),
                     what="gunzip to start before part-1 finished downloading")
            # part-1 is still gated in the network: not completed, job not done
            self.assertFalse(any("downloaded daemon.hprof.gz.part-1" in line
                                 for line in self.jobs.get(job.id).log))
            self.assertNotIn(self.jobs.get(job.id).state,
                             (core.JobState.DONE, core.JobState.FAILED))
        finally:
            gate.set()
        j = wait_job(self.jobs, job)
        self.assertIs(j.state, core.JobState.DONE, msg=j.error)
        wait_all_idle(self.jobs)

    def test_stage_retries_a_transient_network_failure(self):
        """The reported bug class: one network error used to fail the whole
        download. Now the stage retries in place (attempt 2 resumes the kept
        partial) and the user never sees a failure."""
        transfer_mod.DL_RETRIES = 1   # the part level fails fast; the STAGE retries
        plan, payloads = self.make_plan()
        src = FakeSource(plan, payloads, fail_once=("daemon.hprof.gz.part-1",))
        store = self.make_store(src)
        job = store.start_download("run-t")
        j = wait_job(self.jobs, job)
        self.assertIs(j.state, core.JobState.DONE, msg=j.error)
        wait_ready(store, "run-t")
        wait_all_idle(self.jobs)
        self.assertTrue(any("attempt 1/" in line for line in j.log))
        p1 = [c for c in src.calls if c[0] == "daemon.hprof.gz.part-1"]
        self.assertEqual(p1[0], ("daemon.hprof.gz.part-1", 0))
        self.assertTrue(any(off > 0 for _, off in p1[1:]))   # Range-resumed
        self.assertEqual([c for c in src.calls if c[0] == "daemon.hprof.gz.part-0"],
                         [("daemon.hprof.gz.part-0", 0)])   # completed part skipped
        # exactly one job per component — the retry never left the stage
        self.assertEqual(sorted(x.detail for x in self.jobs.list(limit=10)),
                         ["data", "dump", "indexes"])

    def test_failure_keeps_parts_and_resume_continues(self):
        """An exhausted stage is a component ERROR (projection FAILED); the
        kept .dl parts resume on the user's explicit retry."""
        transfer_mod.DL_RETRIES = 1
        stages_mod.STAGE_ATTEMPTS = 1   # no stage retries: straight to ERROR
        plan, payloads = self.make_plan()
        src = FakeSource(plan, payloads, fail_always=("daemon.hprof.gz.part-1",))
        store = self.make_store(src)
        job = store.start_download("run-t")
        j = wait_job(self.jobs, job)
        self.assertIs(j.state, core.JobState.FAILED)
        info = store.get("run-t")
        self.assertIs(info.state, core.DumpState.FAILED)
        self.assertIn("boom", info.error)
        self.assertEqual(machine_of(store, "run-t").dump.s, machine.ERROR)
        dl = os.path.join(self.tmp.name, "run-t", ".dl")
        self.assertTrue(os.path.exists(os.path.join(dl, "daemon.hprof.gz.part-0")))
        self.assertTrue(os.path.exists(os.path.join(dl, "daemon.hprof.gz.part-1.tmp")))
        calls_before = list(src.calls)
        src.fail_always.clear()   # network healed
        job2 = store.start_download("run-t")   # explicit retry resets ERROR
        j2 = wait_job(self.jobs, job2)
        self.assertIs(j2.state, core.JobState.DONE, msg=j2.error)
        wait_ready(store, "run-t")
        wait_all_idle(self.jobs)
        resume = [c for c in src.calls[len(calls_before):]
                  if c[0] == "daemon.hprof.gz.part-1"]
        self.assertTrue(resume and resume[0][1] > 0)   # Range-resumed, not restarted
        self.assertNotIn(("daemon.hprof.gz.part-0", 0),
                         src.calls[len(calls_before):])   # completed part skipped

    def test_progress_is_detailed(self):
        """During the download the job exposes stage/done/total/speed/eta,
        per-part states and the assembly overlap; cleared when finished."""
        plan, payloads = self.make_plan()
        gate = threading.Event()
        src = FakeSource(plan, payloads, gates={"daemon.hprof.gz.part-1": gate})
        store = self.make_store(src)
        job = store.start_download("run-t")
        try:
            wait_for(lambda: (self.jobs.get(job.id).progress or {}).get("stage")
                     == "download", what="download progress")
            p = self.jobs.get(job.id).progress
            self.assertGreater(p["done"], 0)
            self.assertEqual(p["total"], sum(x.size for x in plan.hprof_parts))
            self.assertIn("speed", p)
            self.assertIn("eta", p)
            self.assertEqual({x["n"] for x in p["parts"]},
                             {x.name for x in plan.hprof_parts})
            # assembly overlap is reported while the download still runs
            wait_for(lambda: (self.jobs.get(job.id).progress or {})
                     .get("asm", {}).get("done", 0) > 0,
                     what="assembly overlap bytes")
        finally:
            gate.set()
        j = wait_job(self.jobs, job)
        self.assertIs(j.state, core.JobState.DONE, msg=j.error)
        self.assertIsNone(self.jobs.get(job.id).progress)
        wait_all_idle(self.jobs)

    def test_ready_without_indexes(self):
        """No idx release yet: hprof + data bundle still reach READY; the
        indexes component simply stays NEW (lazy acquisition)."""
        plan, payloads = self.make_plan(with_indexes=False)
        store = self.make_store(FakeSource(plan, payloads))
        job = store.start_download("run-t")
        j = wait_job(self.jobs, job)
        self.assertIs(j.state, core.JobState.DONE, msg=j.error)
        wait_ready(store, "run-t")
        wait_all_idle(self.jobs)
        self.assertEqual(machine_of(store, "run-t").indexes.s, machine.NEW)
        self.assertNotIn("indexes", [x.detail for x in self.jobs.list(limit=10)])


class TestEarlyData(Fixture):
    def test_overview_served_during_download(self):
        plan, payloads = self.make_plan()
        gate = threading.Event()
        src = FakeSource(plan, payloads, gates={"daemon.hprof.gz.part-0": gate,
                                                "indexes.tar.part-0": gate})
        store = self.make_store(src)
        engine = self.make_engine(store)
        store.start_download("run-t")
        try:
            wait_for(lambda: os.path.exists(os.path.join(
                self.tmp.name, "run-t", "data", "histogram.csv")),
                what="data bundle to be unpacked")
            self.assertIs(store.get("run-t").state, core.DumpState.DOWNLOADING)
            t = engine.trees("run-t")
            self.assertEqual(t["stats"]["totalObjects"], 8)
            self.assertEqual(engine.classes("run-t")["total"], 2)
            with self.assertRaises(core.ApiError) as ctx:   # analysis stays READY-only
                engine.analyze("run-t", "org.gradle.Big")
            self.assertEqual(ctx.exception.status, 409)
        finally:
            gate.set()
        wait_ready(store, "run-t")
        wait_all_idle(self.jobs)

    def test_overview_409_until_data_lands(self):
        plan, payloads = self.make_plan()
        gate = threading.Event()
        src = FakeSource(plan, payloads, gates={"data.tar.gz": gate})
        store = self.make_store(src)
        engine = self.make_engine(store)
        store.start_download("run-t")
        try:
            job_by_detail(self.jobs, "run-t", "data")   # the data stage is up
            with self.assertRaises(core.ApiError) as ctx:
                engine.trees("run-t")
            self.assertEqual(ctx.exception.status, 409)
            self.assertIn("no data bundle yet", str(ctx.exception))
        finally:
            gate.set()
        wait_ready(store, "run-t")
        wait_all_idle(self.jobs)

    def test_compare_with_busy_dump(self):
        plan, payloads = self.make_plan()
        src = FakeSource(plan, payloads)
        store = self.make_store(src)
        engine = self.make_engine(store)
        store.start_download("run-t")
        wait_ready(store, "run-t")
        # a second dump stuck mid-download: data unpacked, hprof gated
        gate = threading.Event()
        plan_u = core.DownloadPlan(
            dump_id="run-u", data_bundle=plan.data_bundle,
            hprof_parts=plan.hprof_parts, index_parts=(), manifest={})
        src.plan = {"run-t": plan, "run-u": plan_u}
        src.gates = {"daemon.hprof.gz.part-0": gate}
        store.start_download("run-u")
        try:
            wait_for(lambda: os.path.exists(os.path.join(
                self.tmp.name, "run-u", "data", "histogram.csv")),
                what="run-u data bundle")
            out = engine.compare("run-t", "run-u")
            self.assertEqual(out["new"]["totalObjects"], 8)
            self.assertEqual(out["anats"], {})
        finally:
            gate.set()
        wait_all_idle(self.jobs)


class TestLazyIndexes(Fixture):
    def test_request_indexes_parks_a_local_parse_when_nothing_published(self):
        """No idx release: request_indexes records the intent (PARSING) and
        waits for the analyzing thread to run the local parse inline — no
        job is submitted by reconcile itself."""
        plan, payloads = self.make_plan(with_indexes=False)
        store = self.make_store(FakeSource(plan, payloads))
        self.make_ready_noindex(store)
        self.make_engine(store)
        store.request_indexes("run-t")
        self.assertEqual(machine_of(store, "run-t").indexes.s, machine.PARSING)
        self.assertEqual(self.jobs.list(limit=10), [])
        self.assertIs(store.get("run-t").state, core.DumpState.READY)

    def test_request_indexes_downloads_the_published_set(self):
        plan, payloads = self.make_plan()
        store = self.make_store(FakeSource(plan, payloads))
        d = self.make_ready_noindex(store)
        store.request_indexes("run-t")
        job = job_by_detail(self.jobs, "run-t", "indexes")
        self.assertEqual(job.kind, core.JobKind.DOWNLOAD)
        j = wait_job(self.jobs, job)
        self.assertIs(j.state, core.JobState.DONE, msg=j.error)
        for name, content in IDX.items():
            with open(os.path.join(d, name), "rb") as f:
                self.assertEqual(f.read(), content)
        self.assertIs(store.get("run-t").state, core.DumpState.READY)
        self.assertEqual(store.read_meta("run-t")["indexes"], "remote")

    def test_analyze_downloads_published_indexes_first(self):
        plan, payloads = self.make_plan()
        store = self.make_store(FakeSource(plan, payloads))
        self.make_ready_noindex(store)
        engine = self.make_engine(store)
        called = []
        engine._analyze_class = lambda job, *a: called.append(a)
        job = engine.analyze("run-t", "org.gradle.Big")
        j = wait_job(self.jobs, job)
        self.assertIs(j.state, core.JobState.DONE, msg=j.error)
        self.assertEqual(len(called), 1)
        # the index download ran as a separate DOWNLOAD job, before the analysis
        kinds = [(x.kind, x.detail, x.state) for x in self.jobs.list(limit=10)]
        self.assertIn((core.JobKind.DOWNLOAD, "indexes", core.JobState.DONE), kinds)
        raws, zsts = raws_zsts(os.path.join(self.tmp.name, "run-t"))
        self.assertTrue(zsts)

    def test_analyze_falls_back_to_local_parse(self):
        """Nothing published: the analyze job itself runs the local MAT parse
        INLINE (it holds the serial MAT worker — queueing behind itself would
        deadlock), then proceeds with the analysis."""
        plan, payloads = self.make_plan(with_indexes=False)
        store = self.make_store(FakeSource(plan, payloads))
        d = self.make_ready_noindex(store)
        engine = self.make_engine(store)
        called = []

        def fake_run(job, hprof, outdir, sfx, command, keep_name, **kw):
            with open(os.path.join(d, "daemon.index"), "w") as f:
                f.write("idx")
            return None   # parse_trigger.csv never written — fine

        engine._runner.run = fake_run
        engine._analyze_class = lambda job, *a: called.append(a)
        job = engine.analyze("run-t", "org.gradle.Big")
        j = wait_job(self.jobs, job)
        self.assertIs(j.state, core.JobState.DONE, msg=j.error)
        self.assertEqual(len(called), 1)
        self.assertTrue(any("local MAT parse" in line for line in j.log))
        self.assertEqual(store.read_meta("run-t")["indexes"], "local")
        # no separate INDEX job: the parse ran inside the ANALYZE job
        self.assertNotIn(core.JobKind.INDEX,
                         [x.kind for x in self.jobs.list(limit=10)])


class TestIndexingFill(Fixture):
    """Hprof present, data bundle missing. Nothing local starts unprompted:
    reconcile fills from a late-published release; only an explicit retry
    (start_download) may fall back to the local bootstrap."""

    def test_download_without_data_bundle_goes_indexing_quietly(self):
        plan, payloads = self.make_plan()
        plan = dataclasses.replace(plan, data_bundle=None)
        store = self.make_store(FakeSource(plan, payloads))
        called = []
        store.indexer = lambda dump_id: called.append(dump_id)
        job = store.start_download("run-t")
        j = wait_job(self.jobs, job)
        self.assertIs(j.state, core.JobState.DONE, msg=j.error)
        wait_all_idle(self.jobs)
        self.assertIs(store.get("run-t").state, core.DumpState.INDEXING)
        self.assertEqual(called, [])   # no unprompted local bootstrap
        self.assertNotIn(core.JobKind.INDEX,
                         [x.kind for x in self.jobs.list(limit=10)])

    def test_reconcile_fills_from_a_late_release(self):
        plan, payloads = self.make_plan()
        store = self.make_store(FakeSource(plan, payloads))
        d = self.make_indexing(store)
        submitted = store.reconcile("run-t")   # what the kernel poll does
        self.assertEqual([j.detail for j in submitted], ["data", "indexes"])
        wait_ready(store, "run-t")
        wait_all_idle(self.jobs)
        with open(os.path.join(d, "data", "histogram.csv")) as f:
            self.assertEqual(f.read(), HIST)
        for name in IDX:   # the index tar came along too
            self.assertTrue(os.path.exists(os.path.join(d, name)), name)

    def test_reconcile_idles_when_nothing_published(self):
        plan, payloads = self.make_plan(with_indexes=False)
        plan = dataclasses.replace(plan, data_bundle=None)
        store = self.make_store(FakeSource(plan, payloads))
        self.make_indexing(store)
        self.assertEqual(store.reconcile("run-t"), [])
        self.assertIs(store.get("run-t").state, core.DumpState.INDEXING)

    def test_start_download_on_ready_is_a_409(self):
        plan, payloads = self.make_plan(with_indexes=False)
        store = self.make_store(FakeSource(plan, payloads))
        self.make_ready_noindex(store)
        with self.assertRaises(core.ApiError) as ctx:
            store.start_download("run-t")
        self.assertEqual(ctx.exception.status, 409)

    def test_retry_on_indexing_prefers_remote(self):
        plan, payloads = self.make_plan()
        store = self.make_store(FakeSource(plan, payloads))
        self.make_indexing(store)
        called = []
        store.indexer = lambda dump_id: called.append(dump_id)
        job = store.start_download("run-t")   # explicit retry on INDEXING
        j = wait_job(self.jobs, job)
        self.assertIs(j.state, core.JobState.DONE, msg=j.error)
        wait_ready(store, "run-t")
        wait_all_idle(self.jobs)
        self.assertEqual(called, [])   # remote fill won, no local parse

    def test_retry_on_indexing_without_remote_builds_locally(self):
        plan, payloads = self.make_plan(with_indexes=False)
        plan = dataclasses.replace(plan, data_bundle=None)
        store = self.make_store(FakeSource(plan, payloads))
        self.make_indexing(store)
        called = []
        store.indexer = lambda dump_id: called.append(dump_id) or core.Job(
            id=99, kind=core.JobKind.INDEX, dump_id=dump_id)
        job = store.start_download("run-t")
        self.assertEqual(job.id, 99)
        self.assertEqual(called, ["run-t"])   # explicit trigger -> local bootstrap
        self.assertEqual(machine_of(store, "run-t").data.s, machine.PARSING)
        self.assertIs(store.get("run-t").state, core.DumpState.INDEXING)

    def test_data_failure_projects_failed_and_an_explicit_retry_heals(self):
        transfer_mod.DL_RETRIES = 1
        stages_mod.STAGE_ATTEMPTS = 1
        plan, payloads = self.make_plan()
        src = FakeSource(plan, payloads, fail_always=("data.tar.gz",))
        store = self.make_store(src)
        self.make_indexing(store)
        store.reconcile("run-t")
        j = wait_job(self.jobs, job_by_detail(self.jobs, "run-t", "data"))
        self.assertIs(j.state, core.JobState.FAILED)
        wait_all_idle(self.jobs)
        info = store.get("run-t")
        self.assertIs(info.state, core.DumpState.FAILED)   # data ERROR -> FAILED
        self.assertEqual(machine_of(store, "run-t").data.s, machine.ERROR)
        src.fail_always.clear()
        job2 = store.start_download("run-t")   # explicit retry resets the ERROR
        j2 = wait_job(self.jobs, job2)
        self.assertIs(j2.state, core.JobState.DONE, msg=j2.error)
        wait_ready(store, "run-t")
        wait_all_idle(self.jobs)

    def test_drop_untrusted_raws(self):
        plan, _ = self.make_plan()
        store = self.make_store(FakeSource(plan, {}))
        d = self.make_indexing(store)
        # debris + raws, no .zst: everything dropped (interrupted parse)
        for n in ("daemon.index", "daemon.o2c.index"):
            open(os.path.join(d, n), "w").close()
        open(os.path.join(d, "daemon.temp.outbound.index"), "w").close()
        open(os.path.join(d, "daemon.lock.index"), "w").close()
        self.assertTrue(drop_untrusted_raws(d))
        self.assertEqual(raws_zsts(d), ([], []))
        self.assertEqual(parse_debris(d), [])
        # a compacted .zst set makes raws trustworthy: nothing dropped
        for n in ("daemon.index", "daemon.index.zst", "daemon.lock.index"):
            open(os.path.join(d, n), "w").close()
        self.assertFalse(drop_untrusted_raws(d))
        self.assertTrue(raws_zsts(d)[0])
        # no debris at all: no-op
        os.remove(os.path.join(d, "daemon.lock.index"))
        os.remove(os.path.join(d, "daemon.index.zst"))
        self.assertFalse(drop_untrusted_raws(d))
        self.assertTrue(raws_zsts(d)[0])

    def test_fill_drops_interrupted_parse_indexes(self):
        """A killed local parse leaves a partial raw set + debris; the index
        acquisition must not mistake it for a usable set."""
        plan, payloads = self.make_plan()
        store = self.make_store(FakeSource(plan, payloads))
        d = self.make_indexing(store)
        open(os.path.join(d, "daemon.index"), "w").close()   # partial
        open(os.path.join(d, "daemon.lock.index"), "w").close()
        store.reconcile("run-t")
        j = wait_job(self.jobs, job_by_detail(self.jobs, "run-t", "indexes"))
        self.assertIs(j.state, core.JobState.DONE, msg=j.error)
        wait_ready(store, "run-t")
        wait_all_idle(self.jobs)
        for name, content in IDX.items():
            with open(os.path.join(d, name), "rb") as f:
                self.assertEqual(f.read(), content)   # remote set, not the partial


class TestCorruptionHealing(Fixture):
    """The failure classes that used to lock a dump into a broken state:
    interrupted untars, truncated extracted files, corrupt parts, corrupt
    compacted indexes. Every one must now self-heal."""

    def plant_index_parts(self, d, plan, payloads):
        tmp = os.path.join(d, ".dl")
        os.makedirs(tmp, exist_ok=True)
        for p in plan.index_parts:
            with open(os.path.join(tmp, p.name), "wb") as f:
                f.write(payloads[p.name])
        return tmp

    def test_truncated_extracted_zst_is_redriven_and_healed(self):
        """The reported bug: an aborted untar left a truncated .zst at its
        final path; the completed parts are still in .dl. The persisted
        manifest contradicts the extracted file, so the machine never trusts
        it — the stage drops it and heals the set from the kept parts,
        byte-for-byte, without re-downloading anything."""
        plan, payloads = self.make_plan()
        src = FakeSource(plan, payloads)
        store = self.make_store(src)
        d = self.make_ready_noindex(store, idx_manifest=dict(
            plan.manifest["files"]))
        with open(os.path.join(d, "daemon.index.zst"), "wb") as f:
            f.write(b"zst")   # truncated — the manifest says 7 bytes ("zst-one")
        self.plant_index_parts(d, plan, payloads)
        store.request_indexes("run-t")
        j = wait_job(self.jobs, job_by_detail(self.jobs, "run-t", "indexes"))
        self.assertIs(j.state, core.JobState.DONE, msg=j.error)
        for name, content in IDX.items():
            with open(os.path.join(d, name), "rb") as f:
                self.assertEqual(f.read(), content)
        self.assertEqual([c for c in src.calls if c[0].startswith("indexes.tar")],
                         [])   # healed purely from the kept parts
        self.assertFalse(os.path.exists(os.path.join(d, ".untar")))

    def test_valid_set_with_leftover_parts_is_adopted_and_cleaned(self):
        """A kill AFTER the commit leaves a validated set plus redundant parts
        in .dl: observation validates the set against the persisted manifest,
        the machine adopts it as DONE without any job, and the sweeper pass
        removes the redundant parts."""
        plan, payloads = self.make_plan()
        src = FakeSource(plan, payloads)
        store = self.make_store(src)
        d = self.make_ready_noindex(store, idx_manifest=dict(
            plan.manifest["files"]))
        for name, content in IDX.items():
            with open(os.path.join(d, name), "wb") as f:
                f.write(content)
        tmp = self.plant_index_parts(d, plan, payloads)
        store.request_indexes("run-t")
        self.assertEqual(self.jobs.list(limit=10), [])   # no acquisition needed
        self.assertEqual(src.calls, [])
        m = machine_of(store, "run-t")
        self.assertEqual(m.indexes.s, machine.DONE)
        self.assertTrue(m.indexes.compacted)
        self.assertFalse(os.path.exists(tmp))   # redundant parts swept

    def test_aborted_untar_leaves_no_partial_final_files(self):
        """Killing the untar mid-stream must leave partial members only inside
        the .untar staging dir — never a truncated file at its final path."""
        transfer_mod.DL_RETRIES = 1
        stages_mod.STAGE_ATTEMPTS = 1
        plan, payloads = self.make_plan()
        src = FakeSource(plan, payloads, fail_always=("indexes.tar.part-1",))
        store = self.make_store(src)
        store.start_download("run-t")
        j = wait_job(self.jobs, job_by_detail(self.jobs, "run-t", "indexes"))
        self.assertIs(j.state, core.JobState.FAILED)
        wait_all_idle(self.jobs)
        d = os.path.join(self.tmp.name, "run-t")
        for name in IDX:   # the partial untar stayed inside staging
            self.assertFalse(os.path.exists(os.path.join(d, name)), name)
        # the index failure does not drag the usable dump below READY
        self.assertIs(store.get("run-t").state, core.DumpState.READY)
        self.assertEqual(machine_of(store, "run-t").indexes.s, machine.ERROR)
        src.fail_always.clear()
        job2 = store.start_download("run-t")   # explicit retry re-enters
        j2 = wait_job(self.jobs, job2)
        self.assertIs(j2.state, core.JobState.DONE, msg=j2.error)
        wait_all_idle(self.jobs)
        for name, content in IDX.items():
            with open(os.path.join(d, name), "rb") as f:
                self.assertEqual(f.read(), content)

    def assembly_reject_variant(self, bad_byte):
        plan, payloads = self.make_plan()
        good = payloads["indexes.tar.part-0"]
        payloads["indexes.tar.part-0"] = bad_byte * len(good)   # size-correct garbage
        src = FakeSource(plan, payloads)
        store = self.make_store(src)
        store.start_download("run-t")
        j = wait_job(self.jobs, job_by_detail(self.jobs, "run-t", "indexes"))
        self.assertIs(j.state, core.JobState.FAILED)
        wait_all_idle(self.jobs)
        # the indexes component is ERROR; the usable dump stays READY
        self.assertIs(store.get("run-t").state, core.DumpState.READY)
        self.assertEqual(machine_of(store, "run-t").indexes.s, machine.ERROR)
        dl = os.path.join(self.tmp.name, "run-t", ".dl")
        for n in ("indexes.tar.part-0", "indexes.tar.part-1"):
            self.assertFalse(os.path.exists(os.path.join(dl, n)), n)
        calls_before = list(src.calls)
        payloads["indexes.tar.part-0"] = good   # fixed bytes (e.g. next CDN edge)
        job2 = store.start_download("run-t")
        j2 = wait_job(self.jobs, job2)
        self.assertIs(j2.state, core.JobState.DONE, msg=j2.error)
        wait_all_idle(self.jobs)
        self.assertIs(store.get("run-t").state, core.DumpState.READY)
        refetched = {n for n, off in src.calls[len(calls_before):]
                     if n.startswith("indexes.tar")}
        self.assertEqual(refetched, {"indexes.tar.part-0", "indexes.tar.part-1"})

    def test_assembly_rejects_corrupt_parts_and_drops_them(self):
        """Size-complete but garbage parts: the stage drops the rejected parts
        so the next attempt re-downloads them instead of failing identically
        forever. Two rejection forms, both AssemblyError:
        tar exits non-zero (0xff) — and tar exits ZERO on an all-zeros part,
        which GNU tar reads as a valid empty archive, so only the manifest
        validation of the staged set catches it."""
        with self.subTest(reject="tar rc != 0"):
            self.assembly_reject_variant(b"\xff")
        # fresh dump dir for the second variant
        os.rename(os.path.join(self.tmp.name, "run-t"),
                  os.path.join(self.tmp.name, "run-t-done"))
        with self.subTest(reject="tar rc == 0, manifest validation"):
            self.assembly_reject_variant(b"\x00")

    def test_analyze_recovers_from_corrupt_indexes(self):
        """A .zst that fails decompression is deleted by the restore; the
        analysis drops the untrusted set, hands the component back to the
        machine (remote re-download — it is published here) and retries."""
        plan, payloads = self.make_plan()
        src = FakeSource(plan, payloads)
        store = self.make_store(src)
        d = self.make_ready_noindex(store)
        with open(os.path.join(d, "daemon.index.zst"), "wb") as f:
            f.write(b"zz")   # corrupt
        engine = self.make_engine(store)
        calls = []

        def fake_analyze(job_, *a):
            calls.append(a)
            if len(calls) == 1:
                # exactly what the real restore does: delete, then raise
                os.remove(os.path.join(d, "daemon.index.zst"))
                raise CorruptIndexError("corrupt compacted index deleted: "
                                        "daemon.index.zst")

        engine._analyze_class = fake_analyze
        j = wait_job(self.jobs, engine.analyze("run-t", "org.gradle.Big"))
        self.assertIs(j.state, core.JobState.DONE, msg=j.error)
        self.assertEqual(len(calls), 2)   # failed once, retried after re-fetch
        kinds = [(x.kind, x.detail, x.state) for x in self.jobs.list(limit=10)]
        self.assertIn((core.JobKind.DOWNLOAD, "indexes", core.JobState.DONE), kinds)
        for name, content in IDX.items():
            with open(os.path.join(d, name), "rb") as f:
                self.assertEqual(f.read(), content)

    def test_analyze_corrupt_indexes_nothing_published_heals_via_local_parse(self):
        """No prebuilt set to re-fetch: the partial set is dropped (MAT must
        never run against one) and the machine falls back to a local parse,
        inline in the analyzing thread — no user round-trip."""
        plan, payloads = self.make_plan(with_indexes=False)
        store = self.make_store(FakeSource(plan, payloads))
        d = self.make_ready_noindex(store)
        with open(os.path.join(d, "daemon.index.zst"), "wb") as f:
            f.write(b"zz")
        engine = self.make_engine(store)
        calls = []

        def fake_run(job, hprof, outdir, sfx, command, keep_name, **kw):
            with open(os.path.join(d, "daemon.index"), "w") as f:
                f.write("idx")
            return None

        def fake_analyze(job_, *a):
            calls.append(a)
            if len(calls) == 1:
                raise CorruptIndexError("corrupt compacted index deleted: "
                                        "daemon.index.zst")

        engine._runner.run = fake_run
        engine._analyze_class = fake_analyze
        j = wait_job(self.jobs, engine.analyze("run-t", "org.gradle.Big"))
        self.assertIs(j.state, core.JobState.DONE, msg=j.error)
        self.assertEqual(len(calls), 2)   # corrupt once, healed, analyzed
        self.assertEqual(store.read_meta("run-t")["indexes"], "local")
        raws, zsts = raws_zsts(d)
        self.assertTrue(raws)   # the local parse produced the new set

    def test_data_bundle_meta_merged_not_clobbered(self):
        """The bundle ships data/meta.json (state, modules, ...): its
        non-state fields are merged, but the store-owned live meta (the
        machine, local fields) is never clobbered by the untar."""
        plan, payloads = self.make_plan()
        bundle = _tar({"data/histogram.csv": HIST.encode(),
                       "data/dominator_by_class.csv": DOM.encode(),
                       "data/meta.json": json.dumps(
                           {"state": "ready", "modules": 42,
                            "dump": "daemon.hprof"}).encode()}, mode="w:gz")
        payloads["data.tar.gz"] = bundle
        plan = dataclasses.replace(
            plan, data_bundle=core.Part(name="data.tar.gz", index=0,
                                        size=len(bundle), url="mem://data.tar.gz"))
        store = self.make_store(FakeSource(plan, payloads))
        self.make_indexing(store)
        store.update_meta("run-t", lambda m: m.update(marker="keep"))
        store.reconcile("run-t")
        wait_ready(store, "run-t")
        wait_all_idle(self.jobs)
        meta = store.read_meta("run-t")
        self.assertEqual(meta["modules"], 42)     # merged from the bundle
        self.assertEqual(meta["marker"], "keep")  # live meta survived
        self.assertNotIn("state", meta)           # bundle state never adopted
        self.assertIn("machine", meta)

    def test_corrupt_data_bundle_part_dropped_and_refetched(self):
        """A size-complete but garbage data.tar.gz untars to nothing
        (AssemblyError): the part is dropped, no partial CSVs leak into
        data/, the component goes ERROR, and an explicit retry refetches
        once the bytes are fixed."""
        plan, payloads = self.make_plan()
        good = payloads["data.tar.gz"]
        payloads["data.tar.gz"] = b"\x00" * len(good)
        src = FakeSource(plan, payloads)
        store = self.make_store(src)
        store.start_download("run-t")
        j = wait_job(self.jobs, job_by_detail(self.jobs, "run-t", "data"))
        self.assertIs(j.state, core.JobState.FAILED)
        self.assertIn("tar", j.error)   # AssemblyError: garbage bytes rejected
        wait_all_idle(self.jobs)
        self.assertIs(store.get("run-t").state, core.DumpState.FAILED)
        d = os.path.join(self.tmp.name, "run-t")
        self.assertFalse(os.path.exists(os.path.join(d, ".dl", "data.tar.gz")))
        self.assertFalse(os.path.exists(os.path.join(d, "data", "histogram.csv")))
        payloads["data.tar.gz"] = good
        job2 = store.start_download("run-t")
        j2 = wait_job(self.jobs, job2)
        self.assertIs(j2.state, core.JobState.DONE, msg=j2.error)
        wait_ready(store, "run-t")
        wait_all_idle(self.jobs)


class FakeJobs:
    """Captures submissions without running them (reconcile-tick tests)."""
    def __init__(self, live=()):
        self.submitted = []
        self._live = list(live)

    def submit(self, kind, dump_id, detail, fn):
        job = core.Job(id=len(self.submitted) + 1, kind=kind,
                       dump_id=dump_id, detail=detail)
        self.submitted.append(job)
        return job

    def log(self, job, line):
        pass

    def list(self, limit=30):
        return list(self._live)


class TestReconcileTick(unittest.TestCase):
    """The kernel timers are bare reconcile kicks; the machine decides what
    each dump needs. These tests pin the decisions over planted artifacts."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def make_app(self, plan, live=(), with_data=True, with_indexes=False,
                 comps=None, want_indexes=False):
        jobs = FakeJobs(live)
        src = FakeSource(plan, {})
        store = FsDumpStore(self.tmp.name, jobs, [src])
        store.init()
        d = os.path.join(self.tmp.name, "run-t")
        os.makedirs(os.path.join(d, "data"), exist_ok=True)
        with open(os.path.join(d, "daemon.hprof"), "wb") as f:
            f.write(b"x")
        if with_data:
            with open(os.path.join(d, "data", "histogram.csv"), "w") as f:
                f.write(HIST)
            with open(os.path.join(d, "data", "dominator_by_class.csv"), "w") as f:
                f.write(DOM)
        if with_indexes:
            with open(os.path.join(d, "daemon.index.zst"), "wb") as f:
                f.write(b"z")
        if comps:
            def mut(meta):
                m = machine.Machine(wanted=True, want_indexes=want_indexes)
                for name, c in comps.items():
                    setattr(m, name, c)
                meta["machine"] = machine.machine_to(m)
            store.update_meta("run-t", mut)
        return core.App(store=store, engine=None, jobs=jobs,
                        sources=[store, src]), jobs, store

    def test_submits_download_when_idx_appeared(self):
        plan, _ = Fixture.make_plan()
        app, jobs, _ = self.make_app(plan)
        kernel._reconcile_tick(app)
        self.assertEqual([(j.kind, j.dump_id, j.detail) for j in jobs.submitted],
                         [(core.JobKind.DOWNLOAD, "run-t", "indexes")])

    def test_skips_when_nothing_published(self):
        plan, _ = Fixture.make_plan(with_indexes=False)
        app, jobs, _ = self.make_app(plan)
        kernel._reconcile_tick(app)
        self.assertEqual(jobs.submitted, [])

    def test_skips_with_indexes_present(self):
        plan, _ = Fixture.make_plan()
        app, jobs, _ = self.make_app(plan, with_indexes=True)
        kernel._reconcile_tick(app)
        self.assertEqual(jobs.submitted, [])

    def test_skips_with_live_index_download(self):
        plan, _ = Fixture.make_plan()
        live = [core.Job(id=9, kind=core.JobKind.DOWNLOAD, dump_id="run-t",
                         detail="indexes", state=core.JobState.RUNNING)]
        app, jobs, _ = self.make_app(
            plan, live=live,
            comps={"indexes": machine.Comp(machine.DOWNLOADING)})
        kernel._reconcile_tick(app)
        self.assertEqual(jobs.submitted, [])

    def test_late_publication_preempts_a_local_parse(self):
        """A running local parse is preempted by a late remote publication:
        the abort flag goes up and the prebuilt set is downloaded instead."""
        plan, _ = Fixture.make_plan()
        app, jobs, store = self.make_app(
            plan, comps={"indexes": machine.Comp(machine.PARSING)},
            want_indexes=True)
        store._rt("run-t").inline_indexes.set()   # the inline parse is live
        kernel._reconcile_tick(app)
        self.assertEqual([(j.kind, j.dump_id, j.detail) for j in jobs.submitted],
                         [(core.JobKind.DOWNLOAD, "run-t", "indexes")])
        self.assertTrue(store.abort_event("run-t").is_set())
        self.assertEqual(machine_of(store, "run-t").indexes.s, machine.DOWNLOADING)

    def test_indexing_dump_filled_from_late_release(self):
        plan, _ = Fixture.make_plan()
        app, jobs, _ = self.make_app(plan, with_data=False)
        kernel._reconcile_tick(app)
        self.assertEqual([(j.kind, j.dump_id, j.detail) for j in jobs.submitted],
                         [(core.JobKind.DOWNLOAD, "run-t", "data"),
                          (core.JobKind.DOWNLOAD, "run-t", "indexes")])

    def test_indexing_dump_idles_when_nothing_published(self):
        plan, _ = Fixture.make_plan(with_indexes=False)
        plan = dataclasses.replace(plan, data_bundle=None)
        app, jobs, _ = self.make_app(plan, with_data=False)
        kernel._reconcile_tick(app)
        self.assertEqual(jobs.submitted, [])


if __name__ == "__main__":
    unittest.main()
