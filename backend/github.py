#!/usr/bin/env python3
"""GitHub Releases source: remote dump discovery + downloads (backend impl).

The benchmark repo publishes `run-*`
releases carrying `daemon.hprof.gz.part-*`; this repo's CI publishes matching
`idx-<tag>` releases carrying `indexes.tar.part-*` + `data.tar.gz` +
`manifest.json`. Both repos are public, so anonymous REST works; when the `gh`
CLI is authenticated it is used instead (higher rate limits).

API/transport failures are NEVER swallowed into empty
results — they raise core.ApiError('upstream', ..., status=502). Only a
confirmed-absent release (HTTP 404) means "this source doesn't have it".
"""
import json, os, re, shutil, subprocess, threading, time, urllib.error, urllib.request

from . import core

API = "https://api.github.com"
RUN_RE = re.compile(r"^run-([A-Za-z]+-)*\d+(-(base|candidate))?$")
IDX_RE = re.compile(r"^idx-(.+)$")  # unchanged — already covers new tags
TTL = int(os.environ.get("HEAP_REPORT_REMOTE_TTL", "60"))  # anonymous REST: 60 req/h


def _part_index(name, prefix):
    """Explicit concatenation index from the part suffix: digits stay decimal,
    GNU split's default alphabetic suffixes decode base-26 (aa=0, ab=1, ...).
    Never infer order from name sorting."""
    suffix = name[len(prefix):]
    if suffix.isdigit():
        return int(suffix)
    idx = 0
    for ch in suffix:
        idx = idx * 26 + (ord(ch) - ord("a"))
    return idx


def _asset_map(rel):
    return {a["name"]: a for a in rel.get("assets", [])}


def _split_parts(assets, prefix, single):
    """core.Part list in index order for a possibly-split asset, or None."""
    named = [n for n in assets if n.startswith(prefix)]
    if named:
        parts = [core.Part(name=n, index=_part_index(n, prefix), size=assets[n]["size"],
                           url=assets[n]["browser_download_url"]) for n in named]
        parts.sort(key=lambda p: p.index)
        return parts
    if single in assets:
        a = assets[single]
        return [core.Part(name=single, index=0, size=a["size"],
                          url=a["browser_download_url"])]
    return None


class GitHubSource:
    name = "github"

    def __init__(self, source_repo="kroune/feature-module-3000",
                 index_repo="kroune/heap-report"):
        self.source_repo = source_repo
        self.index_repo = index_repo
        self._gh_ok = None
        self._lock = threading.Lock()
        self._runs_cache = None      # joined remote runs
        self._runs_at = 0.0

    def init(self):
        """Warm the listing cache; tolerate failure (offline start must not crash)."""
        try:
            self._runs()
        except Exception:
            pass

    # ------------------------------------------------------------ GitHub API

    def _gh_available(self):
        if self._gh_ok is None:
            self._gh_ok = bool(shutil.which("gh")) and subprocess.run(
                ["gh", "auth", "status"], capture_output=True).returncode == 0
        return self._gh_ok

    def _api(self, path, allow_404=False):
        """GET one GitHub API page (path starts with /repos/...), parsed JSON.
        None on a confirmed 404 when allow_404; any other failure raises
        core.ApiError('upstream', ..., status=502)."""
        if self._gh_available():
            try:
                r = subprocess.run(["gh", "api", path], capture_output=True,
                                   text=True, timeout=60)
            except Exception as e:
                raise core.ApiError("upstream", f"gh api {path}: {e}", status=502)
            if r.returncode != 0:
                if allow_404 and "HTTP 404" in r.stderr:
                    return None
                raise core.ApiError("upstream",
                                    f"gh api {path}: {r.stderr[-300:]}", status=502)
            try:
                return json.loads(r.stdout)
            except ValueError as e:
                raise core.ApiError("upstream", f"gh api {path}: bad JSON: {e}",
                                    status=502)
        req = urllib.request.Request(API + path, headers={"User-Agent": "heap-report"})
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                return json.load(resp)
        except urllib.error.HTTPError as e:
            if allow_404 and e.code == 404:
                return None
            raise core.ApiError("upstream",
                                f"GitHub API {path}: HTTP {e.code}", status=502)
        except Exception as e:
            raise core.ApiError("upstream", f"GitHub API {path}: {e}", status=502)

    def _list_releases(self, repo, pages=3):
        """Up to `pages`x100 most recent releases, newest first."""
        out = []
        for p in range(1, pages + 1):
            batch = self._api(f"/repos/{repo}/releases?per_page=100&page={p}")
            if not isinstance(batch, list) or not batch:
                break
            out.extend(batch)
            if len(batch) < 100:
                break
        return out

    def _release(self, repo, tag):
        """Release dict for `tag`, or None when it confirmedly doesn't exist."""
        return self._api(f"/repos/{repo}/releases/tags/{tag}", allow_404=True)

    # ------------------------------------------------------------ discovery

    def _runs(self):
        """Benchmark runs joined with their index builds, cached for TTL seconds.

        [{tag, title, created_at, dump_bytes, indexed, idx_built_at, index_bytes}]
        Index-repo failures degrade to indexed=False (runs stay listable);
        source-repo failures raise upstream."""
        with self._lock:
            if self._runs_cache is not None and time.time() - self._runs_at < TTL:
                return self._runs_cache
            idx = {}
            try:
                for rel in self._list_releases(self.index_repo):
                    m = IDX_RE.match(rel.get("tag_name", ""))
                    if m and m.group(1) not in idx:
                        idx[m.group(1)] = rel
            except core.ApiError:
                pass   # index repo unreachable/empty — runs are still listable
            out = []
            for rel in self._list_releases(self.source_repo):
                tag = rel.get("tag_name", "")
                if not RUN_RE.match(tag):
                    continue
                dump = _split_parts(_asset_map(rel), "daemon.hprof.gz.part-",
                                    "daemon.hprof.gz")
                if not dump:
                    continue
                i = idx.get(tag)
                tar = _split_parts(_asset_map(i), "indexes.tar.part-",
                                   "indexes.tar") if i else None
                out.append({
                    "tag": tag,
                    "title": rel.get("name") or tag,
                    "created_at": rel.get("created_at", ""),
                    "dump_bytes": sum(p.size or 0 for p in dump),
                    "indexed": bool(tar),
                    "idx_built_at": (i or {}).get("created_at", ""),
                    "index_bytes": sum(p.size or 0 for p in tar) if tar else 0,
                })
            out.sort(key=lambda r: r["created_at"], reverse=True)
            self._runs_cache = out
            self._runs_at = time.time()
            return out

    def list(self):
        return [core.DumpInfo(
            id=r["tag"], state=core.DumpState.REMOTE, source=self.name,
            size=r["dump_bytes"] + r["index_bytes"] or None,
            meta={"title": r["title"], "created_at": r["created_at"],
                  "indexed": r["indexed"], "idx_built_at": r["idx_built_at"],
                  "dump_bytes": r["dump_bytes"], "index_bytes": r["index_bytes"]})
            for r in self._runs()]

    # ------------------------------------------------------------ downloads

    def download_plan(self, dump_id):
        """None only when the run release or its dump assets are confirmed
        absent; a missing idx release just means index_parts=() (local MAT
        bootstrap fallback). Upstream hiccups raise ApiError, never None."""
        rel = self._release(self.source_repo, dump_id)
        if rel is None:
            return None
        dump = _split_parts(_asset_map(rel), "daemon.hprof.gz.part-",
                            "daemon.hprof.gz")
        if not dump:
            return None
        tar, data, manifest = [], None, {}
        idx_rel = self._release(self.index_repo, f"idx-{dump_id}")
        if idx_rel is not None:
            assets = _asset_map(idx_rel)
            tar = _split_parts(assets, "indexes.tar.part-", "indexes.tar") or []
            if "data.tar.gz" in assets:
                a = assets["data.tar.gz"]
                data = core.Part(name="data.tar.gz", index=0, size=a["size"],
                                 url=a["browser_download_url"])
            if "manifest.json" in assets:
                try:
                    with urllib.request.urlopen(
                            assets["manifest.json"]["browser_download_url"],
                            timeout=30) as resp:
                        manifest = json.load(resp)
                except Exception:
                    pass   # manifest only feeds completeness validation
        return core.DownloadPlan(dump_id=dump_id, data_bundle=data,
                                 hprof_parts=tuple(dump), index_parts=tuple(tar),
                                 manifest=manifest)

    def fetch(self, part, offset=0):
        """Stream one part, resuming at `offset` via HTTP Range. urllib follows
        the release-asset redirect to the CDN. One attempt only — the caller
        owns retry/resume and re-calls fetch with a new offset."""
        req = urllib.request.Request(part.url, headers={"User-Agent": "heap-report"})
        if offset > 0:
            req.add_header("Range", f"bytes={offset}-")
        with urllib.request.urlopen(req, timeout=60) as resp:
            while True:
                chunk = resp.read(1 << 20)
                if not chunk:
                    break
                yield chunk
