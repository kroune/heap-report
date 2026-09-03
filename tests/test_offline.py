"""Offline-behavior tests: with no internet the UI must keep working from
local data, and recover when the network returns.

- GET /api/dumps isolates a failing remote source (local dumps still listed).
- GitHubSource._runs serves the stale listing cache when a refresh fails
  (never an empty result masquerading as "nothing published"); with no cache
  at all it still raises upstream."""
import json
import threading
import unittest
import urllib.request
from http.server import ThreadingHTTPServer

from backend import core, github, http

REL = {"tag_name": "run-1-base", "name": "run 1 base",
       "created_at": "2026-01-01T00:00:00Z",
       "assets": [{"name": "daemon.hprof.gz", "size": 10,
                   "browser_download_url": "http://x/daemon.hprof.gz"}]}


def _offline(repo):
    raise core.ApiError("upstream", f"no route to {repo}", status=502)


class TestStaleRunsCache(unittest.TestCase):
    def _source(self):
        src = github.GitHubSource()
        src._list_releases = (
            lambda repo: [REL] if repo == src.source_repo else [])
        return src

    def test_refresh_failure_serves_stale_cache(self):
        src = self._source()
        runs = src._runs()
        self.assertEqual([r["tag"] for r in runs], ["run-1-base"])
        src._runs_at = 0   # expire the cache so a refresh is attempted
        src._list_releases = _offline
        self.assertEqual(src._runs(), runs)   # stale beats a 502

    def test_no_cache_still_raises(self):
        src = self._source()
        src._list_releases = _offline
        with self.assertRaises(core.ApiError):
            src._runs()

    def test_recovery_replaces_cache(self):
        src = self._source()
        first = src._runs()
        src._runs_at = 0
        src._list_releases = _offline
        src._runs()                        # stale served, cache timestamp kept
        self.assertEqual(src._runs_at, 0)
        src._list_releases = (
            lambda repo: [REL] if repo == src.source_repo else [])
        self.assertEqual(src._runs(), first)
        self.assertGreater(src._runs_at, 0)   # refreshed once back online


class _LocalOnly:
    name = "local"

    def list(self):
        return [core.DumpInfo(id="run-9-base", state=core.DumpState.READY,
                              source="local", size=10, meta={})]

    def user_tags(self):
        return {}


class _DownRemote:
    name = "github"

    def list(self):
        raise core.ApiError("upstream", "offline", status=502)


class _HungRemote:
    """A source whose list() never returns (dead endpoint, no response)."""

    def __init__(self, name="s3"):
        self.name = name
        self.release = threading.Event()

    def list(self):
        self.release.wait(60)   # hangs until the test lets go (daemon thread)


class TestDumpsRouteOffline(unittest.TestCase):
    """A remote source raising upstream must not hide the local listing."""

    def _serve(self, app):
        srv = ThreadingHTTPServer(("127.0.0.1", 0), http._Handler)
        srv.app = app
        t = threading.Thread(target=srv.serve_forever, daemon=True)
        t.start()
        self.addCleanup(srv.shutdown)
        self.addCleanup(srv.server_close)
        return f"http://127.0.0.1:{srv.server_port}/api/dumps"

    def test_local_dumps_listed_when_remote_down(self):
        app = core.App(store=_LocalOnly(), engine=None, jobs=None,
                       sources=[_LocalOnly(), _DownRemote()])
        url = self._serve(app)
        with urllib.request.urlopen(url, timeout=10) as resp:
            self.assertEqual(resp.status, 200)
            body = json.load(resp)
        self.assertEqual([d["id"] for d in body], ["run-9-base"])
        self.assertEqual(body[0]["state"], "ready")

    def test_local_dumps_listed_when_remote_hangs(self):
        """A HUNG source (no error, no response) is skipped at the deadline —
        the listing still returns the local dumps."""
        hung = _HungRemote()
        self.addCleanup(hung.release.set)
        app = core.App(store=_LocalOnly(), engine=None, jobs=None,
                       sources=[_LocalOnly(), hung])
        url = self._serve(app)
        old = http.LIST_TIMEOUT
        http.LIST_TIMEOUT = 1
        try:
            with urllib.request.urlopen(url, timeout=15) as resp:
                self.assertEqual(resp.status, 200)
                body = json.load(resp)
        finally:
            http.LIST_TIMEOUT = old
        self.assertEqual([d["id"] for d in body], ["run-9-base"])

    def test_remote_results_still_merge_under_deadline(self):
        """A fast remote's entries still land in the listing (priority order
        kept: the store's local state wins on duplicate ids)."""
        class _FastRemote:
            name = "github"

            def list(self):
                return [core.DumpInfo(id="run-9-base",   # dup: store wins
                                      state=core.DumpState.REMOTE,
                                      source="github", size=99, meta={}),
                        core.DumpInfo(id="run-10", state=core.DumpState.REMOTE,
                                      source="github", size=5, meta={})]

        app = core.App(store=_LocalOnly(), engine=None, jobs=None,
                       sources=[_LocalOnly(), _FastRemote()])
        url = self._serve(app)
        with urllib.request.urlopen(url, timeout=10) as resp:
            body = json.load(resp)
        by_id = {d["id"]: d for d in body}
        self.assertEqual(by_id["run-9-base"]["state"], "ready")   # local wins
        self.assertEqual(by_id["run-10"]["state"], "remote")


if __name__ == "__main__":
    unittest.main()
