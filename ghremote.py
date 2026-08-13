#!/usr/bin/env python3
"""GitHub release discovery + asset downloads for remote heap dumps.

The benchmark repo (default kroune/feature-module-3000) publishes `run-N` /
`run-N-base|candidate` releases carrying `daemon.hprof.gz[.part-*]`; this repo's CI
publishes matching `idx-<tag>` releases carrying `indexes.tar.part-*` (pre-built,
zstd-compressed MAT indexes) plus a manifest.json. Both repos are public, so plain
unauthenticated REST works; when the `gh` CLI is authenticated it is used instead
(higher rate limits).
"""
import json, os, re, shutil, subprocess, urllib.request

API = "https://api.github.com"
RUN_RE = re.compile(r"^run-\d+(-(base|candidate))?$")
IDX_RE = re.compile(r"^idx-(.+)$")

_gh_ok = None


def _gh_available():
    global _gh_ok
    if _gh_ok is None:
        _gh_ok = bool(shutil.which("gh")) and subprocess.run(
            ["gh", "auth", "status"], capture_output=True).returncode == 0
    return _gh_ok


def _get(path):
    """GET one GitHub API page (path starts with /repos/...), parsed JSON."""
    if _gh_available():
        r = subprocess.run(["gh", "api", path], capture_output=True, text=True, timeout=60)
        if r.returncode != 0:
            raise RuntimeError(f"gh api {path}: {r.stderr[-300:]}")
        return json.loads(r.stdout)
    req = urllib.request.Request(API + path, headers={"User-Agent": "heap-report"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.load(resp)


def list_releases(repo, pages=3):
    """Up to `pages`x100 most recent releases, newest first."""
    out = []
    for p in range(1, pages + 1):
        batch = _get(f"/repos/{repo}/releases?per_page=100&page={p}")
        if not isinstance(batch, list) or not batch:
            break
        out.extend(batch)
        if len(batch) < 100:
            break
    return out


def _asset_map(rel):
    return {a["name"]: a for a in rel.get("assets", [])}


def _dump_assets(rel):
    """daemon.hprof.gz assets in reassembly order: [(name, url, size)], or None."""
    assets = _asset_map(rel)
    parts = sorted(n for n in assets if n.startswith("daemon.hprof.gz.part-"))
    names = parts or (["daemon.hprof.gz"] if "daemon.hprof.gz" in assets else None)
    if not names:
        return None
    return [(n, assets[n]["browser_download_url"], assets[n]["size"]) for n in names]


def _tar_assets(rel):
    assets = _asset_map(rel)
    parts = sorted(n for n in assets if n.startswith("indexes.tar.part-"))
    names = parts or (["indexes.tar"] if "indexes.tar" in assets else None)
    if not names:
        return None
    return [(n, assets[n]["browser_download_url"], assets[n]["size"]) for n in names]


def remote_runs(source_repo, index_repo):
    """Benchmark runs joined with their index builds.

    [{tag, title, created_at, dump_bytes, indexed, idx_built_at, index_bytes}]
    Only runs that actually carry a daemon dump are listed."""
    idx = {}
    try:
        for rel in list_releases(index_repo):
            m = IDX_RE.match(rel.get("tag_name", ""))
            if m and m.group(1) not in idx:
                idx[m.group(1)] = rel
    except Exception:
        pass   # index repo unreachable/empty — runs are still listable
    out = []
    for rel in list_releases(source_repo):
        tag = rel.get("tag_name", "")
        if not RUN_RE.match(tag):
            continue
        dump = _dump_assets(rel)
        if not dump:
            continue
        i = idx.get(tag)
        tar = _tar_assets(i) if i else None
        out.append({
            "tag": tag,
            "title": rel.get("name") or tag,
            "created_at": rel.get("created_at", ""),
            "dump_bytes": sum(s for _, _, s in dump),
            "indexed": bool(tar),
            "idx_built_at": (i or {}).get("created_at", ""),
            "index_bytes": sum(s for _, _, s in tar) if tar else 0,
        })
    out.sort(key=lambda r: r["created_at"], reverse=True)
    return out


def dump_urls(source_repo, tag):
    """Ordered dump assets [(name, url, size)] for the release, or None."""
    rel = _get(f"/repos/{source_repo}/releases/tags/{tag}")
    return _dump_assets(rel)


def index_urls(index_repo, tag):
    """(ordered tar assets [(name, url, size)], data bundle asset or None,
    manifest dict); ([], None, {}) when absent."""
    try:
        rel = _get(f"/repos/{index_repo}/releases/tags/idx-{tag}")
    except Exception:
        return [], None, {}
    tar = _tar_assets(rel) or []
    assets = _asset_map(rel)
    data = None
    if "data.tar.gz" in assets:
        a = assets["data.tar.gz"]
        data = (a["name"], a["browser_download_url"], a["size"])
    manifest = {}
    if "manifest.json" in assets:
        try:
            with urllib.request.urlopen(assets["manifest.json"]["browser_download_url"],
                                        timeout=30) as resp:
                manifest = json.load(resp)
        except Exception:
            pass
    return tar, data, manifest
