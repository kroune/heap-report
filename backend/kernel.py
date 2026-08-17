"""backend.kernel — wires the impls into a core.App and serves it.

  python3 -m backend.kernel [--port 8321] [--dumps <root>]
                            [--source-repo owner/name] [--index-repo owner/name]

Construction order is pinned: jobs -> github source -> store -> engine, then
init() on every source (store first). Also owns the autocompact timer: while
no INDEX/ANALYZE/COMPACT job is active, re-compress compactable dumps.
"""
from __future__ import annotations

import argparse
import logging
import os
import threading
import time

from . import core, http
from . import jobs as jobs_mod, localstore, github, mat

log = logging.getLogger("backend.kernel")

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SOURCE_REPO = os.environ.get("HEAP_REPORT_SOURCE_REPO", "kroune/feature-module-3000")
INDEX_REPO = os.environ.get("HEAP_REPORT_INDEX_REPO", "kroune/heap-report")

AUTOCOMPACT_INTERVAL = int(os.environ.get("HEAP_REPORT_AUTOCOMPACT_INTERVAL", "60"))
_MAT_KINDS = (core.JobKind.INDEX, core.JobKind.ANALYZE, core.JobKind.COMPACT)
_ACTIVE = (core.JobState.QUEUED, core.JobState.RUNNING)


def build_app(root: str, source_repo: str = SOURCE_REPO,
              index_repo: str = INDEX_REPO) -> core.App:
    jobs = jobs_mod.InMemoryJobRegistry()
    gh = github.GitHubSource(source_repo=source_repo, index_repo=index_repo)
    store = localstore.FsDumpStore(root, jobs, [gh])
    engine = mat.MatQueryEngine(store, jobs)
    store.indexer = engine.submit_bootstrap
    for src in (store, gh):
        src.init()
    store.recover_interrupted()   # resubmit work orphaned by a previous process
    return core.App(store, engine, jobs, [store, gh])


def _autocompact_loop(app: core.App) -> None:
    """Every AUTOCOMPACT_INTERVAL seconds, compact idle dumps — but never
    while the serial MAT queue has active work."""
    while True:
        time.sleep(AUTOCOMPACT_INTERVAL)
        try:
            busy = any(j.kind in _MAT_KINDS and j.state in _ACTIVE
                       for j in app.jobs.list(limit=1000))
            if busy:
                continue
            for d in app.store.list_compactable():
                try:
                    app.store.compact(d)
                except Exception:
                    log.exception("autocompact failed for %s", d)
        except Exception:
            log.exception("autocompact tick failed")


def main(argv=None) -> None:
    p = argparse.ArgumentParser(prog="backend.kernel")
    p.add_argument("--port", type=int, default=8321)
    p.add_argument("--dumps",
                   default=os.environ.get("HEAP_REPORT_DUMPS",
                                          os.path.join(REPO, "dumps")))
    p.add_argument("--source-repo", default=SOURCE_REPO)
    p.add_argument("--index-repo", default=INDEX_REPO)
    args = p.parse_args(argv)

    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s %(name)s %(message)s")
    app = build_app(args.dumps, source_repo=args.source_repo,
                    index_repo=args.index_repo)
    t = threading.Thread(target=_autocompact_loop, args=(app,),
                         name="autocompact", daemon=True)
    t.start()
    http.serve(app, args.port)


if __name__ == "__main__":
    main()
