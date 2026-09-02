"""backend.kernel — wires the impls into a core.App and serves it.

  python3 -m backend.kernel [--port 8321] [--dumps <root>]
                            [--source-repo owner/name] [--index-repo owner/name]

Construction order is pinned: jobs -> remote sources (S3 when configured,
strictly preferred; GitHub always) -> store -> engine, then init() on every
source (store first), then store.reconcile_all() to adopt dumps orphaned by a
previous process. Also owns two timers — autocompact and the index/data poll
— which are both just periodic reconcile kicks: the machine decides what each
dump needs (late-published data/indexes, a compact while the MAT queue is
idle, nothing at all).
"""
from __future__ import annotations

import argparse
import logging
import os
import threading
import time

from . import core, http
from . import jobs as jobs_mod, localstore, github, mat, s3

log = logging.getLogger("backend.kernel")

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SOURCE_REPO = os.environ.get("HEAP_REPORT_SOURCE_REPO", "kroune/feature-module-3000")
INDEX_REPO = os.environ.get("HEAP_REPORT_INDEX_REPO", "kroune/heap-report")

AUTOCOMPACT_INTERVAL = int(os.environ.get("HEAP_REPORT_AUTOCOMPACT_INTERVAL", "60"))
IDX_POLL_INTERVAL = int(os.environ.get("HEAP_REPORT_IDX_POLL", "300"))


def build_app(root: str, source_repo: str = SOURCE_REPO,
              index_repo: str = INDEX_REPO) -> core.App:
    jobs = jobs_mod.InMemoryJobRegistry()
    gh = github.GitHubSource(source_repo=source_repo, index_repo=index_repo)
    s3src = s3.S3Source()   # disabled (None-safe) without ~/.aws/credentials
    # download priority: S3 (fast LAN lane) strictly before GitHub; listing
    # order: GitHub first (richer metadata), S3 fills in what GitHub lacks
    remotes = ([s3src] if s3src.enabled else []) + [gh]
    store = localstore.FsDumpStore(root, jobs, remotes)
    engine = mat.MatQueryEngine(store, jobs)
    store.indexer = engine.submit_bootstrap
    store.parser_inline = engine.parse_inline
    for src in (store, *remotes):
        src.init()
    store.reconcile_all()   # resume work orphaned by a previous process
    return core.App(store, engine, jobs,
                    [store, gh] + ([s3src] if s3src.enabled else []))


def _reconcile_tick(app: core.App) -> None:
    """Kick every local dump's machine. Timers never do work themselves —
    the machine figures out the next step: fill data/indexes from a
    late-published release, compact an idle dump, resume an interrupted
    stage, or nothing."""
    for info in app.store.list():
        try:
            app.store.reconcile(info.id)
        except Exception:
            log.exception("reconcile tick failed for %s", info.id)


def _timer_loop(app: core.App, interval: int, name: str) -> None:
    while True:
        time.sleep(interval)
        try:
            _reconcile_tick(app)
        except Exception:
            log.exception("%s tick failed", name)


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
    threading.Thread(target=_timer_loop, args=(app, AUTOCOMPACT_INTERVAL, "autocompact"),
                     name="autocompact", daemon=True).start()
    threading.Thread(target=_timer_loop, args=(app, IDX_POLL_INTERVAL, "index-poll"),
                     name="index-poll", daemon=True).start()
    http.serve(app, args.port)


if __name__ == "__main__":
    main()
