"""S3 source tests (no network): SigV4 signing against botocore-verified
vectors, tag-prefix -> dump-entry mapping, stripped-dump preference (both
sources), the SourceRouter decision table (S3 hit / miss -> GitHub /
error -> GitHub / late arrival after the probe TTL), a mid-download lane
switch through the real transfer machinery, and disabled-without-credentials.
"""
import gzip
import io
import os
import subprocess
import tarfile
import tempfile
import time
import unittest
import unittest.mock
import urllib.error
from datetime import datetime, timezone

from backend import core, github, s3
from backend.jobs import InMemoryJobRegistry
from backend.localstore import FsDumpStore
from backend.localstore import stages as stages_mod
from backend.localstore.store import _merge_plans
from backend.localstore.transfer import DlProgress, SourceRouter, Transfer
from tests import dbfix

HIST = ("Class Name,Objects,Shallow Heap\n"
        "org.gradle.Big,5,200000\n")
DOM = ("Class Name,Objects,Shallow Heap,Retained Heap,x\n"
       "org.gradle.Big,5,200000,900000,z\n")

ACCESS = "AKIDEXAMPLE"
SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
NOW = datetime(2015, 8, 30, 12, 36, tzinfo=timezone.utc)


class TestSigV4(unittest.TestCase):
    """Cross-verified against the botocore signer bundled with aws-cli
    (same request, frozen clock, byte-identical Authorization header)."""

    def test_canonical_get(self):
        h = s3._signed_request(
            "GET", "iam.amazonaws.com", "/",
            [("Action", "ListUsers"), ("Version", "2010-05-08")],
            ACCESS, SECRET, service="iam", now=NOW, unsigned_payload=False)
        self.assertIn(
            "Signature=b2e4af44cfad96d9ffa3c5653674a927b9b0995c33de22e1f843745ce37c1d5e",
            h["Authorization"])
        self.assertIn("Credential=AKIDEXAMPLE/20150830/us-east-1/iam/aws4_request",
                      h["Authorization"])

    def test_path_style_range_get_unsigned_payload(self):
        h = s3._signed_request(
            "GET", "s3.kroune.tech",
            "/heap-reports/run-1/daemon.stripped.hprof.gz", [],
            ACCESS, SECRET, extra={"range": "bytes=1024-"}, now=NOW)
        self.assertIn(
            "Signature=4a750619b5dad7f32ccbe73d56c0b739a130bbf0e30fa42c3d1900018fde2ff7",
            h["Authorization"])
        self.assertIn("SignedHeaders=host;range;x-amz-content-sha256;x-amz-date",
                      h["Authorization"])
        self.assertEqual(h["x-amz-content-sha256"], "UNSIGNED-PAYLOAD")


def _creds_file(tmp):
    p = os.path.join(tmp, "credentials")
    with open(p, "w") as f:
        f.write("[default]\naws_access_key_id = AK\naws_secret_access_key = SK\n")
    return p


def _source(tmp, objects=()):
    src = s3.S3Source(endpoint="https://s3.test", bucket="b",
                      creds_file=_creds_file(tmp))
    src._objects = lambda prefix="": [o for o in objects if o[0].startswith(prefix)]
    src._get_json = lambda key: {"files": {"daemon.index.zst": 3}}
    return src


OBJECTS = [
    ("run-1/daemon.stripped.hprof.gz", 100, "2026-09-01T10:00:00Z"),
    ("run-1/logs.tar.gz", 5, "2026-09-01T09:00:00Z"),
    ("idx-run-1/data.tar.gz", 7, "2026-09-01T11:00:00Z"),
    ("idx-run-1/indexes.tar.zst", 30, "2026-09-01T11:05:00Z"),
    ("idx-run-1/manifest.json", 1, "2026-09-01T11:05:00Z"),
    ("run-2/daemon.hprof.gz", 200, "2026-08-01T10:00:00Z"),  # old: full only
    ("random-thing/x", 1, ""),                               # not a run prefix
    ("idx-orphan/data.tar.gz", 1, ""),                       # idx without a run
]


class TestTagMapping(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def test_runs_listing(self):
        src = _source(self.tmp.name, OBJECTS)
        runs = {r["tag"]: r for r in src._runs()}
        self.assertEqual(sorted(runs), ["run-1", "run-2"])
        r = runs["run-1"]
        self.assertEqual(r["dump_bytes"], 100)          # the stripped dump
        self.assertEqual(r["created_at"], "2026-09-01T10:00:00Z")
        self.assertTrue(r["indexed"])
        self.assertEqual(r["index_bytes"], 30)
        self.assertEqual(r["idx_built_at"], "2026-09-01T11:05:00Z")
        self.assertFalse(runs["run-2"]["indexed"])

    def test_download_plan_prefers_stripped(self):
        src = _source(self.tmp.name, OBJECTS)
        plan = src.download_plan("run-1")
        self.assertEqual([p.name for p in plan.hprof_parts],
                         ["daemon.stripped.hprof.gz"])
        self.assertEqual(plan.hprof_parts[0].url,
                         "https://s3.test/b/run-1/daemon.stripped.hprof.gz")
        self.assertEqual(plan.hprof_parts[0].size, 100)
        self.assertEqual(plan.data_bundle.name, "data.tar.gz")
        self.assertEqual([p.name for p in plan.index_parts], ["indexes.tar.zst"])
        self.assertEqual(plan.manifest, {"files": {"daemon.index.zst": 3}})

    def test_download_plan_falls_back_to_full_dump(self):
        src = _source(self.tmp.name, OBJECTS)
        plan = src.download_plan("run-2")
        self.assertEqual([p.name for p in plan.hprof_parts], ["daemon.hprof.gz"])
        self.assertEqual(plan.index_parts, ())
        self.assertIsNone(plan.data_bundle)

    def test_download_plan_none_when_no_dump(self):
        src = _source(self.tmp.name, OBJECTS)
        self.assertIsNone(src.download_plan("run-99"))
        self.assertIsNone(src.download_plan("idx-run-1"))   # not a run tag

    def test_github_dump_parts_prefers_stripped(self):
        a = {"size": 1, "browser_download_url": "u"}
        both = {"daemon.hprof.gz.part-aa": a, "daemon.hprof.gz.part-ab": a,
                "daemon.stripped.hprof.gz": a}
        self.assertEqual([p.name for p in github._dump_parts(both)],
                         ["daemon.stripped.hprof.gz"])
        self.assertEqual([p.name for p in github._dump_parts(
            {"daemon.hprof.gz.part-ab": a, "daemon.hprof.gz.part-aa": a})],
            ["daemon.hprof.gz.part-aa", "daemon.hprof.gz.part-ab"])  # index order
        self.assertIsNone(github._dump_parts({"logs.tar.gz": a}))

    def test_index_parts_accepts_s3_zst_tar(self):
        a = {"size": 1, "browser_download_url": "u"}
        self.assertEqual([p.name for p in github._index_parts(
            {"indexes.tar.zst": a})], ["indexes.tar.zst"])
        self.assertEqual([p.name for p in github._index_parts(
            {"indexes.tar.part-aa": a})], ["indexes.tar.part-aa"])


class _Head:
    def __init__(self, size):
        self.headers = {"Content-Length": str(size)}

    def close(self):
        pass


class TestOfferProbe(unittest.TestCase):
    """The SourceRouter's per-part probe: S3 hit / miss / error / late
    arrival, size-mismatch refusal, negative caching."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.src = s3.S3Source(endpoint="https://s3.test", bucket="b",
                               creds_file=_creds_file(self.tmp.name))
        self.part = core.Part(name="daemon.stripped.hprof.gz", index=0,
                              size=100, url="https://gh.example/asset")

    def _head(self, key, sizes):
        calls = []

        def req(method, k="", query=(), extra=None, allow_404=False):
            calls.append(k)
            if k in sizes:
                return _Head(sizes[k])
            if allow_404:
                return None
            raise core.ApiError("upstream", "HTTP 404", status=502)
        self.src._req = req
        return calls

    def test_hit_returns_own_part(self):
        self._head("HEAD", {"run-1/daemon.stripped.hprof.gz": 100})
        alt = self.src.offer("run-1", self.part)
        self.assertEqual(alt.url,
                         "https://s3.test/b/run-1/daemon.stripped.hprof.gz")
        self.assertEqual(alt.size, 100)

    def test_miss_and_error_are_cached(self):
        calls = self._head("HEAD", {})
        self.assertIsNone(self.src.offer("run-1", self.part))
        self.assertIsNone(self.src.offer("run-1", self.part))
        self.assertEqual(len(calls), 1)   # negative cached — no HEAD per chunk

        def boom(method, k="", query=(), extra=None, allow_404=False):
            calls.append(k)
            raise core.ApiError("upstream", "connection refused", status=502)
        self.src._req = boom
        self.src._probes.clear()
        self.assertIsNone(self.src.offer("run-1", self.part))   # error -> miss
        self.assertIsNone(self.src.offer("run-1", self.part))
        self.assertEqual(len(calls), 2)   # errors cached too

    def test_late_arrival_after_ttl(self):
        self._head("HEAD", {})
        self.assertIsNone(self.src.offer("run-1", self.part))
        self._head("HEAD", {"run-1/daemon.stripped.hprof.gz": 100})
        key = "run-1/daemon.stripped.hprof.gz"
        self.src._probes[key] = (0.0, None)   # expire the cached miss
        self.assertIsNotNone(self.src.offer("run-1", self.part))

    def test_size_mismatch_is_never_served(self):
        self._head("HEAD", {"run-1/daemon.stripped.hprof.gz": 101})
        self.assertIsNone(self.src.offer("run-1", self.part))

    def test_own_part_passes_through_without_probe(self):
        own = core.Part(name="x", index=0, size=5,
                        url="https://s3.test/b/run-1/x")
        self.src._req = lambda *a, **k: self.fail("no probe for own urls")
        self.assertIs(self.src.offer("run-1", own), own)


class _MemSource:
    """In-memory RemoteDumpSource: fetch honors Range offsets, optional
    one-shot mid-stream failures. Records every call."""

    def __init__(self, name, payloads, fail_once=(), plan=None):
        self.name = name
        self.payloads = payloads
        self.fail_once = set(fail_once)
        self.plan = plan
        self.calls = []
        self._failed = set()

    def owns(self, part):
        return True

    def download_plan(self, dump_id):
        return self.plan

    def fetch(self, part, offset=0):
        self.calls.append((part.name, offset))
        data = self.payloads[part.name]
        for pos in range(offset, len(data), 4096):
            yield data[pos:pos + 4096]
            if part.name in self.fail_once and part.name not in self._failed:
                self._failed.add(part.name)
                raise ConnectionError("boom")


class _ProbeSource(_MemSource):
    """An offer()-capable lane (stand-in for S3Source): serves the part once
    `available` flips, with its own url scheme."""

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self.available = False
        self.offers = 0

    def owns(self, part):
        return part.url.startswith("s3://")

    def offer(self, prefix, part):
        self.offers += 1
        if not self.available:
            return None
        return core.Part(name=part.name, index=part.index, size=part.size,
                         url=f"s3://{prefix}/{part.name}")


def _plan(part, dump_id="run-t"):
    return core.DownloadPlan(dump_id=dump_id, data_bundle=None,
                             hprof_parts=(part,), index_parts=(), manifest={})


class TestSourceRouter(unittest.TestCase):
    def test_hit_wins_over_owner(self):
        part = core.Part(name="daemon.stripped.hprof.gz", index=0, size=10,
                         url="https://gh/x")
        s3s = _ProbeSource("s3", {})
        gh = _MemSource("github", {})
        s3s.available = True
        src, p = SourceRouter(_plan(part), [s3s, gh]).resolve(part)
        self.assertIs(src, s3s)
        self.assertEqual(p.url, "s3://run-t/daemon.stripped.hprof.gz")

    def test_miss_and_error_fall_back_to_owner(self):
        part = core.Part(name="daemon.stripped.hprof.gz", index=0, size=10,
                         url="https://gh/x")
        s3s = _ProbeSource("s3", {})
        gh = _MemSource("github", {})
        router = SourceRouter(_plan(part), [s3s, gh])
        self.assertEqual(router.resolve(part), (gh, part))          # miss
        s3s.offer = lambda prefix, p: (_ for _ in ()).throw(
            core.ApiError("upstream", "down", status=502))
        self.assertEqual(router.resolve(part), (gh, part))          # error

    def test_own_part_not_probed(self):
        part = core.Part(name="indexes.tar.zst", index=0, size=10,
                         url="s3://idx-run-t/indexes.tar.zst")
        s3s = _ProbeSource("s3", {})
        gh = _MemSource("github", {})
        plan = core.DownloadPlan(dump_id="run-t", data_bundle=None,
                                 hprof_parts=(), index_parts=(part,),
                                 manifest={})
        src, p = SourceRouter(plan, [s3s, gh]).resolve(part)
        self.assertEqual((src, p), (s3s, part))
        self.assertEqual(s3s.offers, 0)

    def test_idx_prefix_mapping(self):
        part = core.Part(name="data.tar.gz", index=0, size=10, url="https://gh/d")
        s3s = _ProbeSource("s3", {})
        s3s.available = True
        plan = core.DownloadPlan(dump_id="run-t", data_bundle=part,
                                 hprof_parts=(), index_parts=(), manifest={})
        src, p = SourceRouter(plan, [s3s]).resolve(part)
        self.assertEqual(p.url, "s3://idx-run-t/data.tar.gz")


class _Jobs:
    def log(self, job, line):
        pass


class TestMidDownloadSwitch(unittest.TestCase):
    """A download that started on GitHub switches to S3 mid-part once the
    object appears; the kept partial resumes (Range), bytes are identical."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def _fetch(self, s3_available):
        payload = os.urandom(20000)
        part = core.Part(name="daemon.stripped.hprof.gz", index=0,
                         size=len(payload), url="https://gh/x")
        gh = _MemSource("github", {part.name: payload}, fail_once={part.name})
        s3s = _ProbeSource("s3", {part.name: payload})
        s3s.available = s3_available
        if s3_available:
            # the object appears LATE: the first attempt runs on GitHub and
            # dies mid-stream; the retry's probe finds S3 populated
            s3s.available = False
            real_fetch = gh.fetch

            def fetch_then_flip(p, offset=0):
                try:
                    yield from real_fetch(p, offset)
                finally:
                    s3s.available = True

            gh.fetch = fetch_then_flip
        router = SourceRouter(_plan(part), [s3s, gh])
        job = core.Job(id=1, kind=core.JobKind.DOWNLOAD,
                       dump_id="run-t", detail="dump")
        prog = DlProgress(job)
        Transfer(_Jobs()).fetch_all([part], router, self.tmp.name, job,
                                    lambda m: None, prog)
        with open(os.path.join(self.tmp.name, part.name), "rb") as f:
            self.assertEqual(f.read(), payload)
        return gh, s3s, prog, job

    def test_switch_on_retry(self):
        gh, s3s, prog, job = self._fetch(s3_available=True)
        self.assertEqual(gh.calls, [("daemon.stripped.hprof.gz", 0)])
        s3calls = [c for c in s3s.calls if c[0] == "daemon.stripped.hprof.gz"]
        self.assertEqual(len(s3calls), 1)
        self.assertGreater(s3calls[0][1], 0)   # Range-resumed, not restarted
        self.assertEqual(prog.source, "s3")
        self.assertEqual(job.progress["source"], "s3")

    def test_stays_on_github_without_s3(self):
        gh, s3s, prog, job = self._fetch(s3_available=False)
        self.assertEqual(s3s.calls, [])
        self.assertGreater(gh.calls[1][1], 0)   # retried on GitHub with Range
        self.assertEqual(prog.source, "github")


class TestMergePlans(unittest.TestCase):
    def test_per_component_priority_fill(self):
        p = lambda name: core.Part(name=name, index=0, size=1, url=f"u:{name}")
        s3plan = core.DownloadPlan(dump_id="run-1", data_bundle=None,
                                   hprof_parts=(p("daemon.stripped.hprof.gz"),),
                                   index_parts=(), manifest={})
        ghplan = core.DownloadPlan(dump_id="run-1", data_bundle=p("data.tar.gz"),
                                   hprof_parts=(p("daemon.hprof.gz.part-aa"),),
                                   index_parts=(p("indexes.tar.part-aa"),),
                                   manifest={"files": {}})
        m = _merge_plans(s3plan, ghplan)
        self.assertEqual([x.name for x in m.hprof_parts],
                         ["daemon.stripped.hprof.gz"])   # S3 wins what it has
        self.assertEqual(m.data_bundle.name, "data.tar.gz")   # GitHub fills
        self.assertEqual([x.name for x in m.index_parts],
                         ["indexes.tar.part-aa"])
        self.assertEqual(m.manifest, {"files": {}})


class TestS3PlanEndToEnd(unittest.TestCase):
    """An S3-shaped plan (single unsplit objects, zstd-compressed index tar)
    through the whole store pipeline: gunzip, staged untar via the
    `zstd -dc | tar -x` branch, manifest validation, READY."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self._backoff = stages_mod.STAGE_BACKOFF
        stages_mod.STAGE_BACKOFF = 0   # stage retries are instant in tests

    def tearDown(self):
        stages_mod.STAGE_BACKOFF = self._backoff

    @staticmethod
    def _tar(names, mode="w"):
        buf = io.BytesIO()
        with tarfile.open(fileobj=buf, mode=mode) as tf:
            for name, content in names.items():
                ti = tarfile.TarInfo(name)
                ti.size = len(content)
                tf.addfile(ti, io.BytesIO(content))
        return buf.getvalue()

    def test_single_object_plan_end_to_end(self):
        hprof = os.urandom(50000)
        hgz = gzip.compress(hprof)
        data_tgz = self._tar({"data/histogram.csv": HIST.encode(),
                              "data/dominator_by_class.csv": DOM.encode()},
                             mode="w:gz")
        members = {"daemon.index.zst": b"z-one", "daemon.threads": b"tt"}
        tzst = subprocess.run(["zstd", "-q", "-c"], input=self._tar(members),
                              capture_output=True, check=True).stdout
        mk = lambda name, data: core.Part(name=name, index=0, size=len(data),
                                          url=f"s3://x/{name}")
        plan = core.DownloadPlan(
            dump_id="run-t",
            data_bundle=mk("data.tar.gz", data_tgz),
            hprof_parts=(mk("daemon.stripped.hprof.gz", hgz),),
            index_parts=(mk("indexes.tar.zst", tzst),),
            manifest={"files": {n: len(c) for n, c in members.items()}})
        src = _MemSource("s3", {"daemon.stripped.hprof.gz": hgz,
                                "data.tar.gz": data_tgz,
                                "indexes.tar.zst": tzst}, plan=plan)
        jobs = InMemoryJobRegistry()
        store = FsDumpStore(self.tmp.name, jobs, [src])
        store.init()
        dbfix.wire_ingest_hook(store)
        job = store.start_download("run-t")
        self.assertEqual((job.kind, job.detail), (core.JobKind.DOWNLOAD, "dump"))
        deadline = time.time() + 30
        while time.time() < deadline:
            if store.get("run-t").state is core.DumpState.READY and all(
                    j.state not in (core.JobState.QUEUED, core.JobState.RUNNING)
                    for j in jobs.list(limit=100)):
                break
            time.sleep(0.05)
        else:
            self.fail("dump never became READY")
        d = os.path.join(self.tmp.name, "run-t")
        with open(os.path.join(d, "daemon.hprof"), "rb") as f:
            self.assertEqual(f.read(), hprof)   # LAYOUT.md name unchanged
        for name, content in members.items():
            with open(os.path.join(d, name), "rb") as f:
                self.assertEqual(f.read(), content)
        meta = store.read_meta("run-t")
        self.assertEqual(meta["indexes"], "remote")
        self.assertEqual(meta["idx_manifest"], plan.manifest["files"])


class TestEndpointResolution(unittest.TestCase):
    """Endpoint: explicit arg > HEAP_REPORT_S3_ENDPOINT > ~/.aws/config
    endpoint_url > the https default. The user's machine points the config
    at a plain-http LAN NodePort (RKN throttles the Cloudflare front) — the
    signer signs host+path, scheme-independent."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.creds = _creds_file(self.tmp.name)
        self.config = os.path.join(self.tmp.name, "config")
        with open(self.config, "w") as f:
            f.write("[default]\nendpoint_url = http://192.168.1.101:30333\n")

    def test_config_endpoint(self):
        src = s3.S3Source(creds_file=self.creds, config_file=self.config)
        self.assertEqual(src.endpoint, "http://192.168.1.101:30333")
        self.assertEqual(src._host, "192.168.1.101:30333")   # port signed too
        self.assertEqual(src._base,
                         "http://192.168.1.101:30333/heap-reports")

    def test_default_endpoint_without_config(self):
        src = s3.S3Source(creds_file=self.creds,
                          config_file=os.path.join(self.tmp.name, "missing"))
        self.assertEqual(src.endpoint, "https://s3.kroune.tech")

    def test_env_beats_config_arg_beats_env(self):
        with unittest.mock.patch.dict(
                os.environ, {"HEAP_REPORT_S3_ENDPOINT": "https://env.example"}):
            src = s3.S3Source(creds_file=self.creds, config_file=self.config)
            self.assertEqual(src.endpoint, "https://env.example")
            src = s3.S3Source(endpoint="https://arg.example/",
                              creds_file=self.creds, config_file=self.config)
            self.assertEqual(src.endpoint, "https://arg.example")  # rstrip("/")


class TestRequestTimeouts(unittest.TestCase):
    """Control-plane calls (listing/HEAD/manifest) must fail fast against a
    dead endpoint (TIMEOUT); only the streaming Range GET keeps GET_TIMEOUT."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.src = s3.S3Source(endpoint="https://s3.test", bucket="b",
                               creds_file=_creds_file(self.tmp.name))

    def _timeouts(self, fn):
        calls = []

        def fake_urlopen(req, timeout=None):
            calls.append(timeout)
            raise urllib.error.URLError("timed out")
        with unittest.mock.patch.object(s3.urllib.request, "urlopen",
                                        fake_urlopen):
            with self.assertRaises(core.ApiError):
                fn()
        return calls

    def test_control_plane_uses_short_timeout(self):
        calls = self._timeouts(lambda: self.src._req("GET", query=[("list-type", "2")]))
        self.assertEqual(calls, [s3.TIMEOUT])
        calls = self._timeouts(lambda: self.src._req("HEAD", "run-1/x",
                                                     allow_404=True))
        self.assertEqual(calls, [s3.TIMEOUT])

    def test_streaming_get_keeps_long_timeout(self):
        part = core.Part(name="x", index=0, size=1,
                         url="https://s3.test/b/run-1/x")
        calls = self._timeouts(lambda: next(self.src.fetch(part)))
        self.assertEqual(calls, [s3.GET_TIMEOUT])


class TestDisabled(unittest.TestCase):
    def test_no_credentials_disables_cleanly(self):
        src = s3.S3Source(creds_file=os.path.join(tempfile.gettempdir(),
                                                  "definitely-missing-creds"))
        self.assertFalse(src.enabled)
        self.assertEqual(src.list(), [])
        self.assertIsNone(src.download_plan("run-1"))
        part = core.Part(name="x", index=0, size=1, url="https://gh/x")
        self.assertIsNone(src.offer("run-1", part))
        src.init()   # no crash
        with self.assertRaises(core.ApiError):
            next(src.fetch(part))


if __name__ == "__main__":
    unittest.main()
