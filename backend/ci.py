"""backend.ci — library entry points for build-indexes.yml (the index builder).

CI builds indexes on a GitHub runner; this module lets the workflow drive the
new backend directly instead of the retired flat modules (analyze_dump.py /
compact.py). Not used by the server.
"""
from __future__ import annotations

import os
import time

from . import core, localstore, mat
from . import jobs as jobs_mod


def bootstrap(hprof: str, name: str, outdir: str) -> None:
    """Global extracts (histogram skip-if-present + dominators + meta) for a
    dump dir laid out as <outdir>/{daemon.hprof,data/}. Runs the real
    MatQueryEngine bootstrap against a local-only store and waits for it.
    Raises SystemExit with the job error on failure (CI fails the step)."""
    outdir = os.path.abspath(outdir)
    os.makedirs(os.path.join(outdir, "data"), exist_ok=True)
    root, dump_id = os.path.dirname(outdir), os.path.basename(outdir)
    jobs = jobs_mod.InMemoryJobRegistry()
    store = localstore.FsDumpStore(root, jobs, [])
    engine = mat.MatQueryEngine(store, jobs)
    store.update_meta(dump_id, lambda m: m.update(state="indexing",
                                                  dump=os.path.basename(hprof)))
    job = engine.submit_bootstrap(dump_id)
    while True:
        j = jobs.get(job.id)
        if j.state not in (core.JobState.QUEUED, core.JobState.RUNNING):
            if j.state is core.JobState.FAILED:
                raise SystemExit(f"bootstrap failed:\n{j.error}")
            break
        time.sleep(5)
        for line in j.log[-3:]:
            print(line, flush=True)
    print(f"bootstrap done: {outdir}")


def compact(dump_dir: str) -> None:
    """zstd-compress the dump dir's MAT indexes (mtime convention)."""
    localstore.compact_dir(os.path.abspath(dump_dir), log=print)
